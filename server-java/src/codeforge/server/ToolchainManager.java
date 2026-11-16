package codeforge.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Detects installed language runtimes and downloads portable toolchains into
 * {@code native/toolchains} when a user asks for a language that is missing.
 * Nothing is installed without an explicit request or the auto-install setting.
 */
public final class ToolchainManager {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final String ARCH_RAW = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    private static final String ARCH =
            ARCH_RAW.contains("aarch64") || ARCH_RAW.contains("arm64") ? "arm64"
                    : ARCH_RAW.contains("64") ? "x64" : "x86";
    private static final String EXE = WINDOWS ? ".exe" : "";
    private static final String USER_AGENT = "CodeForge-AI/1.0 (+local toolchain provisioner)";
    private static final String NODE_VERSION = "22.14.0";
    private static final String GO_VERSION = "1.24.1";
    private static final String PYTHON_VERSION = "3.12.9";
    private static final String DOTNET_CHANNEL = "10.0";
    private static final int PARALLEL_CONNECTIONS = 8;
    private static final long PARALLEL_CHUNK = 16L * 1024 * 1024;
    private static final long PARALLEL_THRESHOLD = 32L * 1024 * 1024;

    public static final class Resolved {
        public final String id;
        public final String name;
        public final String version;
        public final String source;
        public final Path executable;
        public final Path binDir;
        public final Path home;
        public final Map<String, String> environment;
        public final String command;

        Resolved(String id, String name, String version, String source,
                 Path executable, Path home, Map<String, String> environment) {
            this(id, name, version, source, executable, home, environment, null);
        }

        Resolved(String id, String name, String version, String source,
                 Path executable, Path home, Map<String, String> environment,
                 String command) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.source = source;
            this.executable = executable;
            this.binDir = executable == null ? null : executable.getParent();
            this.home = home;
            this.environment = environment;
            this.command = command;
        }

        public boolean available() {
            if ("browser".equals(id)) {
                return true;
            }
            if (executable != null && Files.isRegularFile(executable)) {
                return true;
            }
            return executable == null && command != null && !command.isBlank()
                    && !version.isBlank();
        }

        public boolean managed() {
            return "managed".equals(source);
        }
    }

    private static final class RuntimeDef {
        final String id;
        final String name;
        final String hint;
        final boolean installable;
        final double sizeMb;
        final List<String> languages;

        RuntimeDef(String id, String name, String hint, boolean installable,
                   double sizeMb, List<String> languages) {
            this.id = id;
            this.name = name;
            this.hint = hint;
            this.installable = installable;
            this.sizeMb = sizeMb;
            this.languages = languages;
        }
    }

    private static final List<RuntimeDef> DEFS = List.of(
            new RuntimeDef("python", "Python 3",
                    "Install Python 3 and add it to PATH (Windows: python.org installer, Linux: apt install python3, macOS: brew install python).",
                    WINDOWS, 12, List.of("python")),
            new RuntimeDef("gcc", "C / C++ compiler",
                    "Install GCC or LLVM (Windows: MSYS2 or WinLibs, Linux: build-essential, macOS: xcode-select --install).",
                    false, 0, List.of("c", "cpp")),
            new RuntimeDef("java", "Java JDK",
                    "Run python aux-python/bootstrap.py --jdk to provision the portable JDK used by the server.",
                    false, 0, List.of("java")),
            new RuntimeDef("node", "Node.js",
                    "JavaScript and TypeScript execution (Node 22 LTS with native type stripping).",
                    true, 30, List.of("javascript", "typescript")),
            new RuntimeDef("go", "Go",
                    "Official Go toolchain, compiled on demand.",
                    true, 70, List.of("go")),
            new RuntimeDef("rust", "Rust",
                    "Rustup with a minimal stable toolchain. The first install downloads roughly 200 MB.",
                    true, 220, List.of("rust")),
            new RuntimeDef("php", "PHP",
                    "Portable PHP CLI (Windows zip build). Linux and macOS use the system package manager.",
                    WINDOWS, 35, List.of("php")),
            new RuntimeDef("dotnet", ".NET SDK",
                    "Full Microsoft .NET SDK for C# (compiler and templates, not the runtime-only host). The first install downloads roughly 220 MB.",
                    true, 220, List.of("csharp")),
            new RuntimeDef("browser", "HTML / CSS preview",
                    "Built into the console: generated pages render in a sandboxed preview frame.",
                    false, 0, List.of("html", "css")));

    private static final class Job {
        final String runtime;
        volatile String state = "downloading";
        volatile int percent = -1;
        volatile long received;
        volatile long total = -1;
        volatile String message = "starting";
        volatile String error = "";
        volatile String version = "";
        volatile boolean finished;
        final long startedAt = System.currentTimeMillis();
        volatile long finishedAt;

        Job(String runtime) {
            this.runtime = runtime;
        }

        Map<String, Object> toMap() {
            return Json.map(
                    "runtime", runtime,
                    "state", state,
                    "percent", percent,
                    "bytes_received", received,
                    "bytes_total", total,
                    "message", message,
                    "error", error,
                    "version", version,
                    "finished", finished,
                    "started_at", startedAt,
                    "finished_at", finishedAt);
        }
    }

    private final Path root;
    private final Path toolchainDir;
    private final Path downloadDir;
    private final Path stateDir;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final HttpClient parallelHttp = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private volatile Map<String, Resolved> detection = Map.of();
    private volatile long detectionAt;

    public ToolchainManager(Path root) {
        this.root = root;
        this.toolchainDir = root.resolve("native").resolve("toolchains");
        this.downloadDir = toolchainDir.resolve(".downloads");
        this.stateDir = toolchainDir.resolve(".state");
    }

    /* ------------------------------------------------------------------ */
    /* public API                                                         */
    /* ------------------------------------------------------------------ */

    public Map<String, Object> summary() {
        Map<String, Resolved> detected = detection();
        List<Object> runtimes = new ArrayList<>();
        for (RuntimeDef def : DEFS) {
            runtimes.add(snapshot(def, detected.get(def.id)));
        }
        List<Object> languages = new ArrayList<>();
        for (LanguageSupport.Language language : LanguageSupport.all()) {
            Map<String, Object> item = language.describe();
            Resolved resolved = "browser".equals(language.runtime)
                    ? null : detected.get(language.runtime);
            item.put("available", "browser".equals(language.runtime)
                    || (resolved != null && resolved.available()));
            item.put("version", resolved == null ? "" : resolved.version);
            languages.add(item);
        }
        List<Object> activeJobs = new ArrayList<>();
        for (Job job : jobs.values()) {
            activeJobs.add(job.toMap());
        }
        return Json.map(
                "ok", true,
                "platform", WINDOWS ? "windows" : MAC ? "macos" : "linux",
                "arch", ARCH,
                "toolchain_dir", root.relativize(toolchainDir).toString(),
                "runtimes", runtimes,
                "languages", languages,
                "jobs", activeJobs);
    }

    public Map<String, Object> snapshotForLanguage(String languageId) {
        LanguageSupport.Language language = LanguageSupport.byId(languageId);
        if (language == null) {
            return null;
        }
        RuntimeDef def = def(language.runtime);
        Map<String, Object> result = def == null
                ? Json.map("id", language.runtime, "state", "unsupported")
                : snapshot(def, detection().get(def.id));
        result.put("language", language.id);
        result.put("language_name", language.name);
        result.put("auto_install", true);
        return result;
    }

    /** Ensures a runtime is available, starting a background download if allowed. */
    public Map<String, Object> ensure(String runtimeId, boolean allowDownload) {
        return ensure(runtimeId, allowDownload, false);
    }

    private Map<String, Object> ensure(String runtimeId, boolean allowDownload, boolean force) {
        RuntimeDef def = def(runtimeId);
        if (def == null) {
            return Json.map("id", runtimeId, "state", "unsupported",
                    "available", false, "installable", false,
                    "message", "Unknown runtime: " + runtimeId);
        }
        Map<String, Resolved> detected = detection();
        Resolved resolved = detected.get(def.id);
        if (resolved != null && resolved.available()) {
            return snapshot(def, resolved);
        }
        if (!def.installable) {
            return snapshot(def, null);
        }
        Job active = jobs.get(def.id);
        if (active != null && !active.finished) {
            Map<String, Object> running = snapshot(def, null);
            running.put("state", active.state);
            running.put("available", false);
            running.put("job", active.toMap());
            running.put("message", active.message);
            running.put("progress", active.percent);
            return running;
        }
        if (!allowDownload) {
            Map<String, Object> result = snapshot(def, null);
            result.put("state", "missing");
            return result;
        }
        Job job;
        synchronized (jobs) {
            job = jobs.get(def.id);
            if (job != null && job.finished && "failed".equals(job.state) && !force) {
                Map<String, Object> failed = snapshot(def, null);
                failed.put("state", "failed");
                failed.put("error", job.error);
                failed.put("message", job.message);
                return failed;
            }
            if (job == null || job.finished) {
                job = new Job(def.id);
                jobs.put(def.id, job);
                startInstall(def, job);
            }
        }
        Map<String, Object> result = snapshot(def, null);
        result.put("state", job.state);
        result.put("available", false);
        result.put("job", job.toMap());
        result.put("message", job.message);
        result.put("progress", job.percent);
        return result;
    }

    /**
     * Ensures a runtime and waits for a started download to finish. Used by the
     * command agent when a tool is missing and must be provisioned before retry.
     */
    public Map<String, Object> ensureBlocking(String runtimeId,
                                              boolean allowDownload,
                                              long timeoutSeconds,
                                              java.util.function.Consumer<Map<String, Object>> progress) {
        long deadline = System.currentTimeMillis() + Math.max(30, timeoutSeconds) * 1000L;
        Map<String, Object> current = ensure(runtimeId, allowDownload, true);
        while (true) {
            if (progress != null) {
                progress.accept(current);
            }
            String state = Json.str(current, "state", "");
            if ("ready".equals(state) || "failed".equals(state)
                    || "unsupported".equals(state) || "missing".equals(state)) {
                return current;
            }
            if (System.currentTimeMillis() >= deadline) {
                current.put("state", "timeout");
                current.put("timeout", true);
                current.put("error", "toolchain install timed out after "
                        + timeoutSeconds + " seconds");
                return current;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                current.put("state", "cancelled");
                current.put("error", "toolchain install interrupted");
                return current;
            }
            current = ensure(runtimeId, false, false);
        }
    }

    /** Invalidates the detection cache so a freshly installed tool is visible. */
    public void refreshDetection() {
        detection = Map.of();
        detectionAt = 0L;
    }

    public Resolved resolve(String runtimeId) {
        return detection().get(runtimeId);
    }

    public boolean managedInUse(String runtimeId) {
        Resolved resolved = resolve(runtimeId);
        return resolved != null && resolved.available() && resolved.managed();
    }

    public Map<String, String> environment(String runtimeId) {
        Resolved resolved = resolve(runtimeId);
        return resolved == null ? Map.of() : resolved.environment;
    }

    public Map<String, Object> install(String languageOrRuntime) {
        LanguageSupport.Language language = LanguageSupport.byId(languageOrRuntime);
        String runtime = language != null ? language.runtime : languageOrRuntime;
        Map<String, Object> result = ensure(runtime, true, true);
        if (language != null) {
            result.put("language", language.id);
            result.put("language_name", language.name);
        }
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* detection                                                          */
    /* ------------------------------------------------------------------ */

    private Map<String, Resolved> detection() {
        long now = System.currentTimeMillis();
        Map<String, Resolved> cached = detection;
        if (!cached.isEmpty() && now - detectionAt < 25_000L) {
            return cached;
        }
        Map<String, Resolved> fresh = new HashMap<>();
        for (RuntimeDef def : DEFS) {
            fresh.put(def.id, detectOne(def.id));
        }
        detection = java.util.Collections.unmodifiableMap(fresh);
        detectionAt = now;
        return detection;
    }

    private Resolved detectOne(String id) {
        if ("browser".equals(id)) {
            return new Resolved(id, "HTML / CSS preview", "built-in console preview",
                    "built-in", null, null, Map.of());
        }
        Resolved detected = detectSystem(id);
        if (detected != null) {
            return detected;
        }
        return detectManaged(id);
    }

    private Resolved detectSystem(String id) {
        switch (id) {
            case "python": {
                for (String name : new String[]{"python", "python3", "py"}) {
                    Path exe = which(name);
                    String version = exe != null ? probe(exe, "--version", Map.of()) : "";
                    if (version.isBlank()) {
                        version = probe(name, "--version", Map.of());
                    }
                    if (!version.isBlank()) {
                        Resolved python = new Resolved(id, "Python 3", version, "system",
                                exe, null, buildEnvironment(id, null, null), name);
                        return python;
                    }
                }
                return null;
            }
            case "gcc": {
                for (String name : new String[]{"g++", "clang++", "gcc", "clang"}) {
                    Path exe = which(name);
                    if (exe != null) {
                        String version = probe(exe, "--version", Map.of());
                        if (!version.isBlank()) {
                            return runtime(id, "C / C++ compiler", exe,
                                    version, "system", null);
                        }
                    }
                }
                return null;
            }
            case "java": {
                Path jdk = portableJdk();
                Path javac = null;
                if (jdk != null) {
                    javac = jdk.resolve("bin").resolve("javac" + EXE);
                }
                if (javac == null || !Files.isRegularFile(javac)) {
                    javac = which("javac");
                }
                if (javac != null) {
                    String version = probe(javac, "-version", Map.of());
                    if (!version.isBlank()) {
                        return runtime(id, "Java JDK", javac,
                                version, jdk != null ? "portable" : "system", jdk);
                    }
                }
                return null;
            }
            case "node": {
                Path exe = which("node");
                if (exe != null) {
                    String version = probe(exe, "--version", Map.of());
                    if (!version.isBlank()) {
                        return runtime(id, "Node.js", exe, version, "system", null);
                    }
                }
                return null;
            }
            case "go": {
                Path exe = which("go");
                if (exe != null) {
                    String version = probe(exe, "version", Map.of());
                    if (!version.isBlank()) {
                        return runtime(id, "Go", exe, version, "system", null);
                    }
                }
                return null;
            }
            case "rust": {
                Path exe = which("rustc");
                if (exe != null) {
                    String version = probe(exe, "--version", Map.of());
                    if (!version.isBlank()) {
                        return runtime(id, "Rust", exe, version, "system", null);
                    }
                }
                return null;
            }
            case "php": {
                Path exe = which("php");
                if (exe != null) {
                    String version = probe(exe, "--version", Map.of());
                    if (!version.isBlank()) {
                        return runtime(id, "PHP", exe, version, "system", null);
                    }
                }
                return null;
            }
            case "dotnet": {
                Path exe = which("dotnet");
                if (exe != null) {
                    Map<String, String> env = Map.of("DOTNET_NOLOGO", "1",
                            "DOTNET_CLI_TELEMETRY_OPTOUT", "1",
                            "DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1");
                    String sdks = probeRaw(exe, "--list-sdks", env);
                    String version = versionLine(sdks);
                    if (!version.isBlank() && sdks.matches("(?s).*\\d+\\.\\d+.*")) {
                        Path home = exe.getParent();
                        return runtime(id, ".NET SDK", exe, version, "system", home);
                    }
                }
                return null;
            }
            default:
                return null;
        }
    }

    private Resolved detectManaged(String id) {
        Path home = toolchainDir.resolve(id);
        Path marker = home.resolve(".codeforge-bin");
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            String relative = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (relative.isEmpty()) {
                return null;
            }
            Path exe = home.resolve(relative).normalize();
            if (!exe.startsWith(home) || !Files.isRegularFile(exe)) {
                return null;
            }
            String[] args = id.equals("go") ? new String[]{"version"}
                    : id.equals("dotnet") ? new String[]{"--list-sdks"}
                    : id.equals("java") ? new String[]{"-version"}
                    : new String[]{"--version"};
            Map<String, String> env = buildEnvironment(id, exe.getParent(), home);
            String raw = probeRaw(exe, args[0], env);
            String version = versionLine(raw);
            if (id.equals("dotnet") && !raw.matches("(?s).*\\d+\\.\\d+\\.\\d+.*")) {
                return null;
            }
            RuntimeDef def = def(id);
            return runtime(id, def == null ? id : def.name, exe, version, "managed", home);
        } catch (Exception ex) {
            return null;
        }
    }

    private Resolved runtime(String id, String name, Path exe, String version,
                             String source, Path home) {
        return new Resolved(id, name, version, source, exe, home,
                buildEnvironment(id, exe != null ? exe.getParent() : null, home));
    }

    private Map<String, String> buildEnvironment(String id, Path binDir, Path home) {
        Map<String, String> env = new LinkedHashMap<>();
        String path = System.getenv().getOrDefault("PATH", "");
        if (binDir != null) {
            env.put("PATH", binDir + File.pathSeparator + path);
        }
        switch (id) {
            case "go":
                if (home != null && binDir != null && binDir.getParent() != null) {
                    env.put("GOROOT", binDir.getParent().toString());
                }
                try {
                    Files.createDirectories(stateDir.resolve("go-path"));
                    Files.createDirectories(stateDir.resolve("go-cache"));
                } catch (IOException ignored) {
                }
                env.put("GOPATH", stateDir.resolve("go-path").toString());
                env.put("GOCACHE", stateDir.resolve("go-cache").toString());
                break;
            case "rust":
                if (home != null) {
                    env.put("CARGO_HOME", home.resolve("cargo").toString());
                    env.put("RUSTUP_HOME", home.resolve("rustup").toString());
                }
                break;
            case "dotnet":
                if (home != null) {
                    env.put("DOTNET_ROOT", home.toString());
                    env.put("DOTNET_CLI_HOME", stateDir.resolve("dotnet-home").toString());
                }
                env.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
                env.put("DOTNET_NOLOGO", "1");
                env.put("DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1");
                break;
            case "php":
                break;
            default:
                break;
        }
        return env;
    }

    private Path portableJdk() {
        Path jdkRoot = root.resolve("native").resolve("jdk");
        if (!Files.isDirectory(jdkRoot)) {
            return null;
        }
        try (var stream = Files.walk(jdkRoot, 2)) {
            return stream.filter(path -> Files.isRegularFile(path.resolve("bin").resolve("javac" + EXE)))
                    .findFirst().orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private Path which(String name) {
        Path direct = Path.of(name);
        if (direct.isAbsolute() && Files.isRegularFile(direct)) {
            return direct;
        }
        String path = System.getenv().getOrDefault("PATH", "");
        List<String> suffixes = new ArrayList<>();
        if (WINDOWS) {
            suffixes.add(".exe");
            suffixes.add(".cmd");
            suffixes.add(".bat");
            suffixes.add("");
        } else {
            suffixes.add("");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean hasExtension = lower.endsWith(".exe") || lower.endsWith(".cmd")
                || lower.endsWith(".bat");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String suffix : suffixes) {
                if (hasExtension && !suffix.isEmpty()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve(name + suffix);
                if (Files.exists(candidate) && !Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String probe(Path exe, String arg, Map<String, String> extraEnv) {
        return versionLine(probeRaw(exe, arg, extraEnv));
    }

    private String probe(String command, String arg, Map<String, String> extraEnv) {
        return versionLine(probeRaw(Path.of(command), arg, extraEnv));
    }

    private String probeRaw(Path exe, String arg, Map<String, String> extraEnv) {
        try {
            ProcessBuilder builder = new ProcessBuilder(exe.toString(), arg)
                    .redirectErrorStream(true);
            builder.environment().putAll(extraEnv);
            Process process = builder.start();
            boolean done = process.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return "";
            }
            byte[] bytes = process.getInputStream().readAllBytes();
            return Encodings.decode(bytes);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String versionLine(String text) {
        if (text == null) {
            return "";
        }
        String best = "";
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.matches(".*\\d+\\.\\d+.*")) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
            }
            if (best.isEmpty()) {
                best = trimmed;
            }
        }
        return best.length() > 120 ? best.substring(0, 120) : best;
    }

    /* ------------------------------------------------------------------ */
    /* snapshots                                                          */
    /* ------------------------------------------------------------------ */

    private Map<String, Object> snapshot(RuntimeDef def, Resolved resolved) {
        Job job = jobs.get(def.id);
        boolean ready = resolved != null && resolved.available();
        String state;
        if (ready) {
            state = "ready";
        } else if (job != null && !job.finished) {
            state = job.state;
        } else if (job != null && "failed".equals(job.state)) {
            state = "failed";
        } else {
            state = def.installable ? "missing" : "unsupported";
        }
        Map<String, Object> result = Json.map(
                "id", def.id,
                "name", def.name,
                "available", ready,
                "state", state,
                "version", ready ? resolved.version : (job != null ? job.version : ""),
                "source", ready ? resolved.source : "",
                "path", ready && resolved.executable != null ? resolved.executable.toString() : "",
                "managed", ready && resolved.managed(),
                "installable", def.installable,
                "size_mb", def.sizeMb,
                "hint", def.hint,
                "languages", def.languages,
                "message", ready ? "ready" : (job != null ? job.message : def.hint));
        if (job != null) {
            result.put("progress", job.percent);
            result.put("bytes_received", job.received);
            result.put("bytes_total", job.total);
            result.put("error", job.error);
            result.put("job", job.toMap());
        }
        return result;
    }

    private static RuntimeDef def(String id) {
        for (RuntimeDef def : DEFS) {
            if (def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* installation                                                       */
    /* ------------------------------------------------------------------ */

    private void startInstall(RuntimeDef def, Job job) {
        Thread thread = new Thread(() -> install(def, job), "toolchain-" + def.id);
        thread.setDaemon(true);
        thread.start();
    }

    private void install(RuntimeDef def, Job job) {
        Path staging = toolchainDir.resolve("." + def.id + ".staging-" + System.currentTimeMillis());
        try {
            Files.createDirectories(toolchainDir);
            Files.createDirectories(downloadDir);
            Files.createDirectories(stateDir);
            Files.createDirectories(staging);
            switch (def.id) {
                case "node":
                    installArchive(def.id, job, staging, nodeArtifact(),
                            "node" + EXE);
                    break;
                case "go": {
                    Artifact go = goArtifact();
                    installArchive(def.id, job, staging, go, "go" + EXE, List.<String[]>of(
                            new String[]{go.url, go.fileName},
                            new String[]{"https://golang.google.cn/dl/" + go.fileName, go.fileName},
                            new String[]{"https://mirrors.aliyun.com/golang/" + go.fileName, go.fileName},
                            new String[]{"https://mirrors.ustc.edu.cn/golang/" + go.fileName, go.fileName}));
                    break;
                }
                case "python":
                    installArchive(def.id, job, staging, pythonArtifact(), "python" + EXE);
                    break;
                case "php":
                    installPhp(job, staging);
                    break;
                case "rust":
                    installRust(job, staging);
                    break;
                case "dotnet":
                    installDotnet(job, staging);
                    break;
                default:
                    throw new IOException("This runtime has no automatic installer");
            }
            job.state = "verifying";
            job.message = "verifying installation";
            Path finalDir = toolchainDir.resolve(def.id);
            promote(staging, finalDir);
            Resolved resolved = detectManaged(def.id);
            if (resolved == null || !resolved.available()) {
                throw new IOException("The downloaded toolchain could not be verified");
            }
            job.version = resolved.version;
            job.state = "ready";
            job.percent = 100;
            job.message = "ready · " + resolved.version;
            detectionAt = 0L;
            log("installed " + def.id + " -> " + resolved.executable);
        } catch (Exception ex) {
            job.state = "failed";
            job.error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            job.message = "install failed: " + job.error;
            log("install failed for " + def.id + ": " + job.error);
            deleteQuietly(staging);
        } finally {
            job.finished = true;
            job.finishedAt = System.currentTimeMillis();
        }
    }

    private static final class Artifact {
        final String url;
        final String fileName;
        final String exeName;

        Artifact(String url, String fileName, String exeName) {
            this.url = url;
            this.fileName = fileName;
            this.exeName = exeName;
        }
    }

    private Artifact nodeArtifact() throws IOException {
        if (WINDOWS) {
            String arch = "x64".equals(ARCH) ? "x64" : "arm64".equals(ARCH) ? "arm64" : "";
            if (arch.isEmpty()) {
                throw new IOException("Node.js automatic installation does not support " + ARCH_RAW);
            }
            String name = "node-v" + NODE_VERSION + "-win-" + arch + ".zip";
            return new Artifact("https://nodejs.org/dist/v" + NODE_VERSION + "/" + name,
                    name, "node.exe");
        }
        if (MAC) {
            String arch = "arm64".equals(ARCH) ? "arm64" : "x64";
            String name = "node-v" + NODE_VERSION + "-darwin-" + arch + ".tar.gz";
            return new Artifact("https://nodejs.org/dist/v" + NODE_VERSION + "/" + name,
                    name, "node");
        }
        String arch = "arm64".equals(ARCH) ? "arm64" : "x64";
        String name = "node-v" + NODE_VERSION + "-linux-" + arch + ".tar.xz";
        return new Artifact("https://nodejs.org/dist/v" + NODE_VERSION + "/" + name,
                name, "node");
    }

    private Artifact goArtifact() throws IOException {
        String os = WINDOWS ? "windows" : MAC ? "darwin" : "linux";
        String arch = MAC ? ("arm64".equals(ARCH) ? "arm64" : "amd64")
                : ("arm64".equals(ARCH) ? "arm64" : "amd64");
        String suffix = WINDOWS ? ".zip" : ".tar.gz";
        String name = "go" + GO_VERSION + "." + os + "-" + arch + suffix;
        return new Artifact("https://go.dev/dl/" + name, name, "go" + EXE);
    }

    private Artifact pythonArtifact() throws IOException {
        if (!WINDOWS) {
            throw new IOException("Use the system package manager to install Python 3");
        }
        String arch = "arm64".equals(ARCH) ? "arm64" : "amd64";
        String name = "python-" + PYTHON_VERSION + "-embed-" + arch + ".zip";
        return new Artifact("https://www.python.org/ftp/python/" + PYTHON_VERSION + "/" + name,
                name, "python.exe");
    }

    private void installArchive(String runtime, Job job, Path staging,
                                Artifact artifact, String exeName) throws Exception {
        installArchive(runtime, job, staging, artifact, exeName,
                List.<String[]>of(new String[]{artifact.url, artifact.fileName}));
    }

    private void installArchive(String runtime, Job job, Path staging,
                                Artifact artifact, String exeName,
                                List<String[]> candidates) throws Exception {
        Map<String, Object> downloaded = downloadWithFallback(candidates, job);
        Path archive = Path.of(String.valueOf(downloaded.get("file")));
        job.state = "extracting";
        job.message = "extracting " + archive.getFileName();
        extract(archive, staging, job);
        Path exe = locate(staging, exeName);
        if (exe == null) {
            throw new IOException("Archive did not contain " + exeName);
        }
        writeMarker(staging, exe);
        job.state = "installing";
    }

    private void installPhp(Job job, Path staging) throws Exception {
        if (!WINDOWS) {
            throw new IOException("Install PHP with the system package manager "
                    + "(apt install php-cli / brew install php)");
        }
        List<String[]> candidates = List.of(
                new String[]{"https://windows.php.net/downloads/releases/latest/php-8.4-nts-Win32-vs17-x64-latest.zip",
                        "php-8.4-nts-Win32-vs17-x64-latest.zip"},
                new String[]{"https://windows.php.net/downloads/releases/latest/php-8.3-nts-Win32-vs16-x64-latest.zip",
                        "php-8.3-nts-Win32-vs16-x64-latest.zip"},
                new String[]{"https://windows.php.net/downloads/releases/latest/php-8.2-nts-Win32-vs16-x64-latest.zip",
                        "php-8.2-nts-Win32-vs16-x64-latest.zip"});
        Map<String, Object> downloaded = downloadWithFallback(candidates, job);
        Path archive = Path.of(String.valueOf(downloaded.get("file")));
        job.state = "extracting";
        job.message = "extracting " + archive.getFileName();
        extract(archive, staging, job);
        Path exe = locate(staging, "php.exe");
        if (exe == null) {
            throw new IOException("PHP archive did not contain php.exe");
        }
        writeMarker(staging, exe);
        job.state = "installing";
    }

    private void installRust(Job job, Path staging) throws Exception {
        String triple;
        if (WINDOWS) {
            triple = "arm64".equals(ARCH) ? "aarch64-pc-windows-msvc" : "x86_64-pc-windows-msvc";
        } else if (MAC) {
            triple = "arm64".equals(ARCH) ? "aarch64-apple-darwin" : "x86_64-apple-darwin";
        } else {
            triple = "arm64".equals(ARCH) ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu";
        }
        String fileName = "rustup-init" + EXE;
        String url = "https://static.rust-lang.org/rustup/dist/" + triple + "/" + fileName;
        Map<String, Object> downloaded = downloadWithFallback(
                List.<String[]>of(new String[]{url, fileName}), job);
        Path installer = Path.of(String.valueOf(downloaded.get("file")));
        if (!WINDOWS) {
            try {
                Files.setPosixFilePermissions(installer,
                        EnumSet.of(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
            }
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CARGO_HOME", staging.resolve("cargo").toString());
        env.put("RUSTUP_HOME", staging.resolve("rustup").toString());
        env.put("RUSTUP_INIT_SKIP_PATH_CHECK", "yes");
        List<String> command = new ArrayList<>();
        command.add(installer.toString());
        command.add("-y");
        command.add("--no-modify-path");
        command.add("--profile");
        command.add("minimal");
        command.add("--default-toolchain");
        command.add("stable");
        if (WINDOWS && !"arm64".equals(ARCH) && detectSystem("gcc") != null) {
            command.add("--default-host");
            command.add("x86_64-pc-windows-gnu");
        }
        job.message = "running rustup (the stable toolchain is downloaded next)";
        ProcessResult result = runProcess(command, env, staging, 1800, 1024 * 1024, job);
        if (result.exitCode != 0) {
            throw new IOException("rustup-init failed: " + tail(result.output, 400));
        }
        Path exe = locate(staging, "rustc" + EXE);
        if (exe == null) {
            throw new IOException("rustup finished but rustc was not found");
        }
        writeMarker(staging, exe);
        job.state = "installing";
    }

    private void installDotnet(Job job, Path staging) throws Exception {
        String version = latestDotnetSdk(job);
        String arch = "arm64".equals(ARCH) ? "arm64"
                : "x86".equals(ARCH) && ARCH_RAW.contains("arm") ? "arm"
                : "x86".equals(ARCH) ? "x86" : "x64";
        String rid;
        if (WINDOWS) {
            rid = "win-" + arch;
        } else if (MAC) {
            rid = "osx-" + ("arm64".equals(arch) ? "arm64" : "x64");
        } else {
            rid = "linux-" + arch;
        }
        String suffix = WINDOWS ? ".zip" : ".tar.gz";
        String fileName = "dotnet-sdk-" + version + "-" + rid + suffix;
        String path = "/dotnet/Sdk/" + version + "/" + fileName;
        List<String[]> candidates = List.of(
                new String[]{"https://builds.dotnet.microsoft.com" + path, fileName},
                new String[]{"https://dotnetcli.azureedge.net" + path, fileName},
                new String[]{"https://dotnetcli.blob.core.windows.net" + path, fileName});
        Map<String, Object> downloaded = downloadWithFallback(candidates, job);
        Path archive = Path.of(String.valueOf(downloaded.get("file")));
        job.state = "extracting";
        job.message = "extracting " + archive.getFileName();
        extract(archive, staging, job);
        Path exe = locate(staging, "dotnet" + EXE);
        if (exe == null) {
            throw new IOException(".NET SDK archive did not contain dotnet" + EXE);
        }
        String sdks = probeRaw(exe, "--list-sdks",
                buildEnvironment("dotnet", exe.getParent(), staging));
        if (!sdks.matches("(?s).*\\d+\\.\\d+\\.\\d+.*")) {
            throw new IOException(".NET archive exposed no SDK; a runtime-only build was rejected");
        }
        if (!WINDOWS) {
            try {
                Files.setPosixFilePermissions(exe, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
            }
        }
        writeMarker(staging, exe);
        job.state = "installing";
    }

    private String latestDotnetSdk(Job job) throws Exception {
        String channelPath = "/dotnet/release-metadata/" + DOTNET_CHANNEL + "/releases.json";
        List<String> errors = new ArrayList<>();
        for (String host : List.of("https://builds.dotnet.microsoft.com",
                "https://dotnetcli.azureedge.net",
                "https://dotnetcli.blob.core.windows.net")) {
            try {
                job.message = "resolving the latest .NET " + DOTNET_CHANNEL + " SDK";
                Object parsed = Json.parse(fetchText(host + channelPath));
                if (parsed instanceof Map<?, ?> map) {
                    Object latest = map.get("latest-sdk");
                    if (latest != null
                            && String.valueOf(latest).matches("\\d+\\.\\d+\\.\\d+")) {
                        return String.valueOf(latest);
                    }
                }
                errors.add("no latest-sdk from " + host);
            } catch (Exception ex) {
                errors.add(ex.getMessage());
            }
        }
        throw new IOException("Could not resolve the latest .NET SDK: "
                + String.join(" | ", errors));
    }

    private String fetchText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, */*")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private void writeMarker(Path staging, Path exe) throws IOException {
        Path relative = staging.relativize(exe);
        Files.writeString(staging.resolve(".codeforge-bin"), relative.toString(),
                StandardCharsets.UTF_8);
    }

    private void promote(Path staging, Path finalDir) throws IOException {
        Path backup = null;
        if (Files.exists(finalDir)) {
            backup = toolchainDir.resolve("." + finalDir.getFileName()
                    + ".old-" + System.currentTimeMillis());
            Files.move(finalDir, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            moveWithRetry(staging, finalDir);
            if (backup != null) {
                deleteQuietly(backup);
            }
        } catch (IOException ex) {
            if (backup != null && !Files.exists(finalDir)) {
                try {
                    moveWithRetry(backup, finalDir);
                } catch (IOException ignored) {
                }
            }
            throw ex;
        }
    }

    // Windows Defender and indexers briefly hold handles on freshly written
    // executables, which makes the promotion rename fail on the first attempt.
    private static void moveWithRetry(Path source, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (!Files.exists(source) && Files.exists(target)) {
                return;
            }
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException ex) {
                last = ex;
                try {
                    Thread.sleep(200L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last;
    }

    private Map<String, Object> downloadWithFallback(List<String[]> candidates, Job job)
            throws Exception {
        List<String> errors = new ArrayList<>();
        for (String[] candidate : candidates) {
            try {
                Path target = downloadDir.resolve(candidate[1]);
                download(candidate[0], target, job);
                return Json.map("file", target.toString(), "url", candidate[0]);
            } catch (Exception ex) {
                errors.add(ex.getMessage());
            }
        }
        throw new IOException("Every mirror failed: " + String.join(" | ", errors));
    }

    private void download(String url, Path target, Job job) throws Exception {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long already = Files.isRegularFile(part) ? Files.size(part) : 0L;
        RangeProbe probe = null;
        try {
            probe = probeRange(url);
        } catch (Exception ignored) {
        }
        if (probe != null && probe.total > 0) {
            if (already == probe.total) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                job.total = probe.total;
                job.received = probe.total;
                job.percent = 100;
                return;
            }
            if (already > probe.total) {
                Files.deleteIfExists(part);
                already = 0L;
            }
            long remaining = probe.total - already;
            if (probe.ranged && remaining >= PARALLEL_THRESHOLD) {
                try {
                    parallelDownload(url, target, part, already, probe.total, job);
                    return;
                } catch (Exception ex) {
                    log("parallel download failed, retrying single stream: " + ex.getMessage());
                }
            }
        }
        singleDownload(url, target, job);
    }

    private static final class RangeProbe {
        final long total;
        final boolean ranged;

        RangeProbe(long total, boolean ranged) {
            this.total = total;
            this.ranged = ranged;
        }
    }

    private RangeProbe probeRange(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")
                .GET()
                .build();
        HttpResponse<InputStream> response = parallelHttp.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream ignored = response.body()) {
            String contentRange = response.headers().firstValue("Content-Range").orElse("").trim();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("bytes\\s+0-0/(\\d+)").matcher(contentRange);
            if (response.statusCode() == 206 && matcher.find()) {
                return new RangeProbe(Long.parseLong(matcher.group(1)), true);
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new RangeProbe(length, false);
        }
    }

    private void singleDownload(String url, Path target, Job job) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
            }
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        job.total = total;
        job.message = "downloading " + target.getFileName();
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(part)) {
            byte[] buffer = new byte[128 * 1024];
            long received = 0;
            long lastUpdate = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                received += read;
                job.received = received;
                if (total > 0) {
                    job.percent = (int) Math.min(100, received * 100 / total);
                }
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 250) {
                    lastUpdate = now;
                    job.message = "downloading " + target.getFileName() + " · "
                            + human(received) + (total > 0 ? " / " + human(total) : "");
                }
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void parallelDownload(String url, Path target, Path prefix, long already,
                                  long total, Job job) throws Exception {
        long remaining = total - already;
        int workers = (int) Math.max(1, Math.min(PARALLEL_CONNECTIONS,
                (remaining + PARALLEL_CHUNK - 1) / PARALLEL_CHUNK));
        long slice = (remaining + workers - 1) / workers;
        AtomicLong received = new AtomicLong(already);
        job.total = total;
        job.received = already;
        job.percent = (int) Math.min(100, already * 100 / total);
        job.message = "downloading " + target.getFileName() + " · "
                + human(already) + " / " + human(total) + " · " + workers + " streams";
        List<Path> slices = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                long start = already + slice * i;
                long end = Math.min(total - 1, start + slice - 1);
                if (start > end) {
                    break;
                }
                Path sliceFile = target.resolveSibling(target.getFileName() + ".part." + i);
                Files.deleteIfExists(sliceFile);
                slices.add(sliceFile);
                long from = start;
                long to = end;
                futures.add(pool.submit(() -> {
                    downloadSlice(url, sliceFile, from, to, received, job, total);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception ex) {
            for (Future<?> future : futures) {
                future.cancel(true);
            }
            throw ex;
        } finally {
            pool.shutdownNow();
        }
        job.message = "assembling " + target.getFileName();
        Path assembling = target.resolveSibling(target.getFileName() + ".assembling");
        try (OutputStream output = Files.newOutputStream(assembling)) {
            if (already > 0 && Files.isRegularFile(prefix)) {
                Files.copy(prefix, output);
            }
            for (Path sliceFile : slices) {
                Files.copy(sliceFile, output);
            }
        }
        long size = Files.size(assembling);
        if (size != total) {
            throw new IOException("Downloaded file is incomplete: "
                    + size + " of " + total + " bytes");
        }
        Files.move(assembling, target, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(prefix);
        for (Path sliceFile : slices) {
            Files.deleteIfExists(sliceFile);
        }
        job.received = total;
        job.percent = 100;
    }

    private void downloadSlice(String url, Path slice, long start, long end,
                               AtomicLong received, Job job, long total) throws Exception {
        long expected = end - start + 1;
        int attempts = 0;
        while (true) {
            long offset = start + (Files.isRegularFile(slice) ? Files.size(slice) : 0L);
            if (offset > end) {
                return;
            }
            try {
                if (offset == start) {
                    Files.deleteIfExists(slice);
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(30))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "*/*")
                        .header("Range", "bytes=" + offset + "-" + end)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = parallelHttp.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 206) {
                    try (InputStream ignored = response.body()) {
                    }
                    throw new IOException("HTTP " + response.statusCode()
                            + " for range " + offset + "-" + end);
                }
                try (InputStream input = response.body();
                     OutputStream output = Files.newOutputStream(slice,
                             StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    byte[] buffer = new byte[128 * 1024];
                    long lastUpdate = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        long now = received.addAndGet(read);
                        job.received = now;
                        job.percent = (int) Math.min(100, now * 100 / total);
                        long clock = System.currentTimeMillis();
                        if (clock - lastUpdate > 300) {
                            lastUpdate = clock;
                            job.message = "downloading "
                                    + slice.getFileName().toString().replace(".part.", " · stream ")
                                    + " · " + human(now) + " / " + human(total);
                        }
                    }
                }
                if (Files.size(slice) < expected) {
                    throw new IOException("Short range read at " + offset);
                }
                return;
            } catch (Exception ex) {
                if (++attempts >= 3) {
                    throw new IOException("Range download failed: " + ex.getMessage(), ex);
                }
                Thread.sleep(1000);
            }
        }
    }

    private void extract(Path archive, Path target, Job job) throws Exception {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        Files.createDirectories(target);
        if (name.endsWith(".zip")) {
            unzip(archive, target, job);
            return;
        }
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".tar.xz")) {
            ProcessBuilder builder = new ProcessBuilder("tar", "-xf",
                    archive.toString(), "-C", target.toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean done = process.waitFor(30, TimeUnit.MINUTES);
            if (!done) {
                process.destroyForcibly();
                throw new IOException("tar extraction timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IOException("tar failed: " + tail(output, 400));
            }
            return;
        }
        throw new IOException("Unsupported archive type: " + archive.getFileName());
    }

    private void unzip(Path archive, Path target, Job job) throws IOException {
        long entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = target.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(target)) {
                    throw new IOException("Archive entry escapes the target directory");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    try (OutputStream output = Files.newOutputStream(resolved)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                entries++;
                if ((entries & 63) == 0) {
                    job.message = "extracting " + entries + " files";
                }
                zip.closeEntry();
            }
        }
        if (entries == 0) {
            throw new IOException("Archive was empty");
        }
    }

    private static Path locate(Path base, String name) throws IOException {
        try (var stream = Files.walk(base, 8)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> paths = new ArrayList<>();
            stream.forEach(paths::add);
            paths.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
            for (Path item : paths) {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : "…" + trimmed.substring(trimmed.length() - max);
    }

    private static final class ProcessResult {
        int exitCode;
        String output = "";
    }

    private ProcessResult runProcess(List<String> command, Map<String, String> env,
                                     Path workdir, int timeoutSeconds, int captureLimit,
                                     Job job) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workdir.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int limitBytes = captureLimit * 3;
        Thread reader = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = process.getInputStream().read(buffer)) != -1) {
                    synchronized (output) {
                        int room = limitBytes - output.size();
                        if (room > 0) {
                            output.write(buffer, 0, Math.min(room, read));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "toolchain-output");
        reader.setDaemon(true);
        reader.start();
        boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            reader.join(2000);
            throw new IOException("Installer timed out after " + timeoutSeconds + " seconds");
        }
        reader.join(3000);
        ProcessResult result = new ProcessResult();
        result.exitCode = process.exitValue();
        synchronized (output) {
            result.output = Encodings.decode(output.toByteArray());
        }
        return result;
    }

    private void log(String message) {
        try {
            Path logDir = Path.of(root.toString(), "logs");
            Files.createDirectories(logDir);
            Files.writeString(logDir.resolve("toolchains.log"),
                    Instant.now() + "  " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
