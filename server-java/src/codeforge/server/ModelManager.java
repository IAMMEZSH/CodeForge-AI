package codeforge.server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModelManager {
    public static final String RUNTIME_JOB_ID = "llama-runtime";
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
    private static final boolean IS_MAC = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("mac");
    private static final boolean IS_LINUX = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("linux");
    private static final String ARCH = System.getProperty("os.arch", "")
            .toLowerCase(java.util.Locale.ROOT);
    private static final boolean IS_ARM64 = ARCH.contains("aarch64") || ARCH.contains("arm64");
    private static final boolean IS_X64 = ARCH.contains("x86_64") || ARCH.contains("amd64")
            || ARCH.contains("x64");
    private static final String TERMUX_PREFIX = System.getenv().getOrDefault("PREFIX", "");
    private static final boolean IS_TERMUX = IS_LINUX
            && (System.getenv("TERMUX_VERSION") != null || TERMUX_PREFIX.contains("com.termux"));
    private static final boolean IS_MUSL = IS_LINUX && !IS_TERMUX && detectMusl();
    private static final List<String> RUNTIME_TAGS = buildRuntimeTags();
    private static final List<Pattern> RUNTIME_PATTERNS = RUNTIME_TAGS.stream()
            .map(ModelManager::runtimeAssetPattern).toList();
    private static final String RUNTIME_SERVER_NAME =
            IS_WINDOWS ? "llama-server.exe" : "llama-server";
    private static final String DEFAULT_MODEL_MIRROR = "https://hf-mirror.com";
    private static final String MODEL_SOURCE_FALLBACK = "https://huggingface.co";

    private static List<String> buildRuntimeTags() {
        if (IS_WINDOWS) {
            return IS_ARM64 ? List.of("win-cpu-arm64", "win-cpu-x64") : List.of("win-cpu-x64");
        }
        if (IS_MAC) {
            return List.of(IS_ARM64 ? "macos-arm64" : "macos-x64");
        }
        if (IS_TERMUX) {
            return IS_ARM64 ? List.of("android-arm64", "android-arm64-snapdragon") : List.of();
        }
        if (IS_MUSL) {
            return List.of();
        }
        if (IS_LINUX) {
            if (IS_ARM64) {
                return List.of("ubuntu-arm64");
            }
            if (ARCH.contains("s390x")) {
                return List.of("ubuntu-s390x");
            }
            if (IS_X64) {
                return List.of("ubuntu-x64");
            }
        }
        return List.of();
    }

    private static Pattern runtimeAssetPattern(String tag) {
        return Pattern.compile("^llama-.*-bin-" + Pattern.quote(tag)
                + "\\.(?:zip|tar\\.gz)$", Pattern.CASE_INSENSITIVE);
    }

    private static String runtimeLabel() {
        return RUNTIME_TAGS.isEmpty()
                ? "manual install (" + ARCH + ")"
                : String.join(" / ", RUNTIME_TAGS);
    }

    private static boolean detectMusl() {
        try (var files = Files.list(Path.of("/lib"))) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith("ld-musl-"));
        } catch (Exception ex) {
            return false;
        }
    }

    private static String runtimeHint() {
        if (IS_MUSL) {
            return "Alpine/musl has no glibc llama.cpp build; compile llama.cpp locally and set "
                    + "llm.llama_server in config.json (or CODEFORGE_LLAMA_SERVER).";
        }
        if (IS_TERMUX && !IS_ARM64) {
            return "Build llama.cpp from source for this Android ABI and set llm.llama_server in config.json.";
        }
        if (IS_LINUX) {
            return "Build llama.cpp from source, install llama-server, and set llm.llama_server in config.json.";
        }
        return "Build llama.cpp from source and set llm.llama_server in config.json.";
    }

    private static final List<Map<String, Object>> REGISTRY = List.of(
            Json.map(
                    "id", "qwen3-coder-30b-a3b",
                    "name", "Qwen3-Coder 30B-A3B",
                    "vendor", "Alibaba Qwen",
                    "origin", "China",
                    "category", "Agentic flagship",
                    "params", "30B MoE · 3B active",
                    "context", "256K",
                    "license", "Apache-2.0",
                    "repo", "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF",
                    "file", "Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf",
                    "size_display", "≈ 18.6 GB",
                    "min_ram_gb", 24,
                    "recommended", true,
                    "description", "Flagship open agentic coder. Excels at repository-scale refactors, tool use and multi-file fixes; sparse MoE keeps CPU decoding fast."),
            Json.map(
                    "id", "deepseek-coder-v2-lite",
                    "name", "DeepSeek-Coder V2 Lite",
                    "vendor", "DeepSeek",
                    "origin", "China",
                    "category", "MoE efficiency",
                    "params", "16B MoE · 2.4B active",
                    "context", "128K",
                    "license", "DeepSeek License",
                    "repo", "bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
                    "file", "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf",
                    "size_display", "≈ 10.4 GB",
                    "min_ram_gb", 14,
                    "recommended", false,
                    "description", "Sparse MoE coder with 128K context. Only 2.4B parameters are active per token, so it stays quick on CPU while handling large files."),
            Json.map(
                    "id", "deepseek-r1-0528-qwen3-8b",
                    "name", "DeepSeek R1 0528 Qwen3 8B",
                    "vendor", "DeepSeek",
                    "origin", "China",
                    "category", "Reasoning",
                    "params", "8B",
                    "context", "128K",
                    "license", "MIT",
                    "repo", "unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF",
                    "file", "DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf",
                    "size_display", "≈ 5.0 GB",
                    "min_ram_gb", 8,
                    "recommended", false,
                    "description", "Distilled DeepSeek reasoning model. Exposes a chain of thought and is unusually strong at diagnosing logic and concurrency bugs for an 8B model."),
            Json.map(
                    "id", "qwen3-4b-instruct-2507",
                    "name", "Qwen3 4B Instruct 2507",
                    "vendor", "Alibaba Qwen",
                    "origin", "China",
                    "category", "Lightweight",
                    "params", "4B",
                    "context", "256K",
                    "license", "Apache-2.0",
                    "repo", "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                    "file", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                    "size_display", "≈ 2.5 GB",
                    "min_ram_gb", 6,
                    "recommended", false,
                    "description", "Fast lightweight generalist with strong instruction following. Ideal for autocomplete, explanations and machines with 8 GB of RAM."),
            Json.map(
                    "id", "yi-coder-9b-chat",
                    "name", "Yi-Coder 9B Chat",
                    "vendor", "01.AI",
                    "origin", "China",
                    "category", "Code specialist",
                    "params", "9B",
                    "context", "128K",
                    "license", "Apache-2.0",
                    "repo", "bartowski/Yi-Coder-9B-Chat-GGUF",
                    "file", "Yi-Coder-9B-Chat-Q4_K_M.gguf",
                    "size_display", "≈ 5.3 GB",
                    "min_ram_gb", 8,
                    "recommended", false,
                    "description", "Code-specialized 9B covering 52 languages with 128K context. A solid balance for daily edits and cross-file reasoning."),
            Json.map(
                    "id", "codegeex4-all-9b",
                    "name", "CodeGeeX4 ALL 9B",
                    "vendor", "Zhipu AI",
                    "origin", "China",
                    "category", "Code specialist",
                    "params", "9B",
                    "context", "128K",
                    "license", "CodeGeeX4 License",
                    "repo", "bartowski/CodeGeeX4-ALL-9B-GGUF",
                    "file", "codegeex4-all-9b-Q4_K_M.gguf",
                    "size_display", "≈ 6.3 GB",
                    "min_ram_gb", 9,
                    "recommended", false,
                    "description", "All-in-one Chinese and English code model for completion, generation, explanation and tool calling. Runs comfortably on 12 GB of RAM."),
            Json.map(
                    "id", "seed-oss-36b-instruct",
                    "name", "Seed-OSS 36B Instruct",
                    "vendor", "ByteDance Seed",
                    "origin", "China",
                    "category", "Large context",
                    "params", "36B",
                    "context", "512K",
                    "license", "Apache-2.0",
                    "repo", "unsloth/Seed-OSS-36B-Instruct-GGUF",
                    "file", "Seed-OSS-36B-Instruct-Q4_K_M.gguf",
                    "size_display", "≈ 21.8 GB",
                    "min_ram_gb", 27,
                    "recommended", false,
                    "description", "ByteDance 36B long-context model with a 512K window. Strong planning and code synthesis when the machine can spare around 24 GB of RAM."),
            Json.map(
                    "id", "gpt-oss-20b",
                    "name", "GPT-OSS 20B",
                    "vendor", "OpenAI",
                    "origin", "Global",
                    "category", "Reasoning MoE",
                    "params", "20B MoE · 3.6B active",
                    "context", "128K",
                    "license", "Apache-2.0",
                    "repo", "ggml-org/gpt-oss-20b-GGUF",
                    "file", "gpt-oss-20b-MXFP4.gguf",
                    "size_display", "≈ 12.1 GB",
                    "min_ram_gb", 16,
                    "recommended", true,
                    "description", "OpenAI's open-weight MoE reasoning model. 3.6B active parameters deliver excellent debugging and tool use at CPU-friendly speed."),
            Json.map(
                    "id", "devstral-small-2507",
                    "name", "Devstral Small 2507",
                    "vendor", "Mistral AI",
                    "origin", "Global",
                    "category", "Agentic SWE",
                    "params", "24B",
                    "context", "128K",
                    "license", "Apache-2.0",
                    "repo", "unsloth/Devstral-Small-2507-GGUF",
                    "file", "Devstral-Small-2507-Q4_K_M.gguf",
                    "size_display", "≈ 14.3 GB",
                    "min_ram_gb", 20,
                    "recommended", false,
                    "description", "Mistral's software-engineering specialist, tuned for agentic repository edits. Apache-2.0 and highly capable at multi-step fixes."),
            Json.map(
                    "id", "phi-4",
                    "name", "Phi-4",
                    "vendor", "Microsoft",
                    "origin", "Global",
                    "category", "Reasoning",
                    "params", "14B",
                    "context", "16K",
                    "license", "MIT",
                    "repo", "bartowski/phi-4-GGUF",
                    "file", "phi-4-Q4_K_M.gguf",
                    "size_display", "≈ 9.1 GB",
                    "min_ram_gb", 12,
                    "recommended", false,
                    "description", "Microsoft's 14B reasoning model with strong math and code performance. Compact enough for laptops, with a 16K context."),
            Json.map(
                    "id", "gemma-3-27b-it",
                    "name", "Gemma 3 27B IT",
                    "vendor", "Google",
                    "origin", "Global",
                    "category", "Generalist",
                    "params", "27B",
                    "context", "128K",
                    "license", "Gemma License",
                    "repo", "ggml-org/gemma-3-27b-it-GGUF",
                    "file", "gemma-3-27b-it-Q4_K_M.gguf",
                    "size_display", "≈ 16.6 GB",
                    "min_ram_gb", 22,
                    "recommended", false,
                    "description", "Google's 27B multimodal family with strong general reasoning and code explanation in 140+ languages. Requires a workstation-grade machine."),
            Json.map(
                    "id", "codestral-22b",
                    "name", "Codestral 22B",
                    "vendor", "Mistral AI",
                    "origin", "Global",
                    "category", "Code completion",
                    "params", "22B",
                    "context", "32K",
                    "license", "Mistral Non-Production",
                    "repo", "bartowski/Codestral-22B-v0.1-GGUF",
                    "file", "Codestral-22B-v0.1-Q4_K_M.gguf",
                    "size_display", "≈ 13.3 GB",
                    "min_ram_gb", 18,
                    "recommended", false,
                    "description", "Mistral's code-completion specialist with fill-in-the-middle. The weights use a non-production license — review it before commercial use."));

    private final Path root;
    private final Path modelsDir;
    private final Path downloadsDir;
    private final Path installedFile;
    private final Path runtimeDir;
    private final NativeTools nativeTools;
    private final HttpClient http;
    private final Map<String, Map<String, Object>> installed = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public ModelManager(Path root, NativeTools nativeTools) {
        this.root = root;
        this.modelsDir = root.resolve("models");
        this.downloadsDir = modelsDir.resolve("downloads");
        this.installedFile = modelsDir.resolve("installed.json");
        this.runtimeDir = root.resolve("runtime");
        this.nativeTools = nativeTools;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        try {
            Files.createDirectories(downloadsDir);
            Files.createDirectories(runtimeDir);
        } catch (IOException ex) {
            System.err.println("[models] cannot create directories: " + ex.getMessage());
        }
        loadInstalled();
    }

    public static List<Map<String, Object>> registry() {
        return REGISTRY;
    }

    public synchronized Map<String, Object> summary() {
        List<Object> models = new ArrayList<>();
        for (Map<String, Object> entry : REGISTRY) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            String id = Json.str(entry, "id", "");
            Map<String, Object> installedInfo = installed.get(id);
            boolean isInstalled = installedInfo != null && Files.isRegularFile(installedPath(id));
            copy.put("installed", isInstalled);
            if (isInstalled) {
                copy.put("installed_info", new LinkedHashMap<>(installedInfo));
            }
            models.add(copy);
        }
        return Json.map("ok", true, "registry", models,
                        "installed_count", countInstalled());
    }

    public synchronized List<Object> jobsSnapshot() {
        List<Job> snapshot = new ArrayList<>(jobs.values());
        snapshot.sort(Comparator.comparingLong(job -> job.startedAt));
        List<Object> result = new ArrayList<>();
        for (Job job : snapshot) {
            result.add(job.toMap());
        }
        return result;
    }

    public synchronized boolean isModelInstalled(String id) {
        Map<String, Object> info = installed.get(id);
        return info != null && Files.isRegularFile(installedPath(id));
    }

    public synchronized Path installedModelPath(String id) {
        if (!isModelInstalled(id)) {
            return null;
        }
        return installedPath(id);
    }

    public synchronized Map<String, Object> installedModelInfo(String id) {
        Map<String, Object> info = installed.get(id);
        return info == null ? null : new LinkedHashMap<>(info);
    }

    public Map<String, Object> startModelDownload(String id) {
        Map<String, Object> entry = findEntry(id);
        if (entry == null) {
            return Json.map("ok", false, "error", "Unknown model: " + id);
        }
        synchronized (this) {
            if (isModelInstalled(id)) {
                return Json.map("ok", false, "error", "This model is already downloaded and installed.");
            }
            Job existing = jobs.get(id);
            if (existing != null && existing.isActive()) {
                return Json.map("ok", false, "error", "This model is already downloading or verifying.");
            }
            Job job = new Job(id, "model", id, Json.str(entry, "name", id));
            jobs.put(id, job);
            Thread worker = new Thread(() -> runModelDownload(entry, job),
                                       "model-download-" + id);
            worker.setDaemon(true);
            worker.start();
            return Json.map("ok", true, "job", job.toMap());
        }
    }

    public Map<String, Object> resolveModel(String id) {
        Map<String, Object> entry = findEntry(id);
        if (entry == null) {
            return Json.map("ok", false, "error", "Unknown model: " + id);
        }
        try {
            Map<String, Object> meta = resolveHuggingFace(entry);
            Map<String, Object> result = Json.map("ok", true,
                    "id", id,
                    "repo", Json.str(entry, "repo", ""),
                    "file", Json.str(entry, "file", ""),
                    "url", Json.str(meta, "url", ""),
                    "source", Json.str(meta, "source", ""),
                    "size", Json.lon(meta, "size", 0L),
                    "sha256", Json.str(meta, "sha256", ""),
                    "installed", isModelInstalled(id));
            return result;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", "Failed to resolve model metadata: " + ex.getMessage());
        }
    }

    public Map<String, Object> startRuntimeInstall() {
        synchronized (this) {
            Job existing = jobs.get(RUNTIME_JOB_ID);
            if (existing != null && existing.isActive()) {
                return Json.map("ok", false, "error", "The llama.cpp runtime is already being installed.");
            }
            Job job = new Job(RUNTIME_JOB_ID, "runtime", "", "llama.cpp CPU runtime");
            jobs.put(RUNTIME_JOB_ID, job);
            Thread worker = new Thread(() -> runRuntimeInstall(job), "runtime-install");
            worker.setDaemon(true);
            worker.start();
            return Json.map("ok", true, "job", job.toMap());
        }
    }

    public synchronized Map<String, Object> runtimeFiles() {
        Path infoFile = runtimeDir.resolve("runtime.json");
        if (!Files.isRegularFile(infoFile)) {
            Path configured = configuredRuntimeServer();
            Map<String, Object> empty = Json.map("ok", false, "installed", false,
                    "available", configured != null,
                    "platform", runtimeLabel(), "arch", ARCH);
            if (configured != null) {
                empty.put("configured_server", configured.toString());
            }
            return empty;
        }
        try {
            Map<String, Object> info = Json.asObject(
                    Json.parse(Files.readString(infoFile, StandardCharsets.UTF_8)));
            Path server = root.resolve(Json.str(info, "server", ""));
            boolean exists = Files.isRegularFile(server);
            Map<String, Object> result = new LinkedHashMap<>(info);
            Path configured = configuredRuntimeServer();
            result.put("ok", exists);
            result.put("installed", exists);
            result.put("available", exists || configured != null);
            result.put("arch", ARCH);
            result.put("server_absolute", server.toString());
            if (configured != null) {
                result.put("configured_server", configured.toString());
            }
            return result;
        } catch (Exception ex) {
            return Json.map("ok", false, "installed", false,
                            "error", "Cannot read runtime.json: " + ex.getMessage());
        }
    }

    public synchronized Path runtimeServerPath() {
        Path configured = configuredRuntimeServer();
        if (configured != null) {
            return configured;
        }
        Map<String, Object> info = runtimeFiles();
        if (!Json.bool(info, "installed", false)) {
            return null;
        }
        String path = Json.str(info, "server_absolute", "");
        if (path.isEmpty()) {
            return null;
        }
        Path server = Path.of(path);
        return Files.isRegularFile(server) ? server : null;
    }

    private Path configuredRuntimeServer() {
        Path fromEnv = existingRuntimeServer(
                System.getenv().getOrDefault("CODEFORGE_LLAMA_SERVER", ""));
        if (fromEnv != null) {
            return fromEnv;
        }
        try {
            Path configFile = root.resolve("config.json");
            if (!Files.isRegularFile(configFile)) {
                return null;
            }
            Map<String, Object> doc = Json.asObject(
                    Json.parse(Files.readString(configFile, StandardCharsets.UTF_8)));
            return existingRuntimeServer(
                    Json.str(Json.asObject(doc.get("llm")), "llama_server", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path existingRuntimeServer(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            Path candidate = Path.of(raw);
            if (!candidate.isAbsolute()) {
                candidate = root.resolve(candidate).normalize();
            }
            if (!Files.isRegularFile(candidate)) {
                return null;
            }
            if (!IS_WINDOWS && !Files.isExecutable(candidate)) {
                return null;
            }
            return candidate;
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized int countInstalled() {
        int count = 0;
        for (String id : installed.keySet()) {
            if (Files.isRegularFile(installedPath(id))) {
                ++count;
            }
        }
        return count;
    }

    private Map<String, Object> findEntry(String id) {
        for (Map<String, Object> entry : REGISTRY) {
            if (id != null && id.equals(Json.str(entry, "id", ""))) {
                return entry;
            }
        }
        return null;
    }

    private Path installedPath(String id) {
        Map<String, Object> info = installed.get(id);
        if (info == null) {
            return null;
        }
        String relative = Json.str(info, "path", "");
        return relative.isEmpty() ? null : root.resolve(relative);
    }

    private void loadInstalled() {
        if (!Files.isRegularFile(installedFile)) {
            return;
        }
        try {
            String text = Files.readString(installedFile, StandardCharsets.UTF_8);
            Map<String, Object> parsed = Json.asObject(Json.parse(text));
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                Map<String, Object> info = Json.asObject(entry.getValue());
                if (!info.isEmpty()) {
                    installed.put(entry.getKey(), info);
                }
            }
        } catch (Exception ex) {
            System.err.println("[models] cannot load installed.json: " + ex.getMessage());
        }
    }

    private synchronized void saveInstalled() {
        try {
            Files.createDirectories(modelsDir);
            Map<String, Object> payload = new LinkedHashMap<>(installed);
            Path temp = installedFile.resolveSibling("installed.json.tmp");
            Files.writeString(temp, Json.write(payload), StandardCharsets.UTF_8);
            try {
                Files.move(temp, installedFile,
                           StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temp, installedFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            System.err.println("[models] cannot save installed.json: " + ex.getMessage());
        }
    }

    private void runModelDownload(Map<String, Object> entry, Job job) {
        String id = Json.str(entry, "id", "");
        try {
            job.setStatus("resolving", "Resolving model metadata...");
            Map<String, Object> meta = resolveHuggingFace(entry);
            long expectedSize = Json.lon(meta, "size", 0L);
            String expectedSha = Json.str(meta, "sha256", "");
            String url = Json.str(meta, "url", "");
            job.setTotal(expectedSize);
            ensureDiskSpace(expectedSize);

            Path destination = downloadsDir.resolve(id)
                    .resolve(Json.str(entry, "file", "model.gguf"));
            Path part = destination.resolveSibling(destination.getFileName() + ".part");
            Files.createDirectories(destination.getParent());

            job.setStatus("downloading", "Downloading " + Json.str(entry, "name", id) + "...");
            String hash = downloadTo(url, part, job, expectedSize, expectedSha);
            long bytes = Files.size(part);

            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> info = Json.map(
                    "id", id,
                    "name", Json.str(entry, "name", id),
                    "repo", Json.str(entry, "repo", ""),
                    "file", Json.str(entry, "file", ""),
                    "path", root.relativize(destination).toString(),
                    "bytes", bytes,
                    "sha256", hash,
                    "downloaded_at", Instant.now().toString());
            synchronized (this) {
                installed.put(id, info);
                saveInstalled();
            }
            job.complete("Download complete, SHA-256 verified.");
        } catch (Exception ex) {
            job.fail("Download failed: " + ex.getMessage());
            System.err.println("[models] download " + id + " failed: " + ex);
        }
    }

    private void runRuntimeInstall(Job job) {
        Path staging = null;
        Path archivePath = null;
        try {
            job.setStatus("resolving", "Resolving the latest llama.cpp CPU runtime (" +
                    runtimeLabel() + ")...");
            Map<String, Object> asset = resolveLlamaRuntime();
            String url = Json.str(asset, "url", "");
            String tag = Json.str(asset, "tag", "");
            String platform = Json.str(asset, "platform", runtimeLabel());
            long size = Json.lon(asset, "size", 0L);
            String digest = Json.str(asset, "digest", "");
            String expectedSha = digest.startsWith("sha256:")
                    ? digest.substring("sha256:".length()) : "";
            job.setTotal(size);
            ensureDiskSpace(size);

            Path downloads = runtimeDir.resolve("downloads");
            Files.createDirectories(downloads);
            String archiveName = Json.str(asset, "name", RUNTIME_SERVER_NAME + ".zip");
            String suffix = archiveName.toLowerCase().endsWith(".tar.gz")
                    ? ".tar.gz" : ".zip";
            archivePath = downloads.resolve("llama-runtime" + suffix);
            Path part = archivePath.resolveSibling("llama-runtime" + suffix + ".part");

            job.setStatus("downloading", "Downloading llama.cpp " + tag + "...");
            String hash = downloadTo(url, part, job, size, expectedSha);
            Files.move(part, archivePath, StandardCopyOption.REPLACE_EXISTING);

            job.setStatus("extracting", "Extracting runtime...");
            staging = runtimeDir.resolve("llama.cpp.staging-" +
                    Long.toHexString(System.nanoTime()));
            Files.createDirectories(staging);
            extractArchive(archivePath, staging);
            Path server = findFile(staging, RUNTIME_SERVER_NAME);
            if (server == null) {
                throw new IOException("Archive does not contain " + RUNTIME_SERVER_NAME);
            }
            String serverRelative = staging.relativize(server).toString();
            Path installDir = runtimeDir.resolve("llama.cpp");
            Path backup = null;
            if (Files.exists(installDir)) {
                backup = runtimeDir.resolve("llama.cpp.old-" +
                        Long.toHexString(System.nanoTime()));
                Files.move(installDir, backup);
            }
            try {
                Files.move(staging, installDir);
            } catch (IOException moveError) {
                if (backup != null && !Files.exists(installDir)) {
                    Files.move(backup, installDir);
                }
                throw moveError;
            }
            if (backup != null) {
                deleteRecursive(backup);
            }
            Path finalServer = installDir.resolve(serverRelative);
            if (!IS_WINDOWS) {
                finalServer.toFile().setExecutable(true, false);
            }
            Map<String, Object> runtimeInfo = Json.map(
                    "version", tag,
                    "platform", platform,
                    "arch", ARCH,
                    "server", root.relativize(finalServer).toString(),
                    "size", size,
                    "sha256", hash,
                    "archive", Json.str(asset, "name", ""),
                    "installed_at", Instant.now().toString());
            Path infoFile = runtimeDir.resolve("runtime.json");
            Files.writeString(infoFile, Json.write(runtimeInfo), StandardCharsets.UTF_8);
            job.complete("llama.cpp runtime " + tag + " installed.");
            staging = null;
        } catch (Exception ex) {
            job.fail("Runtime installation failed: " + ex.getMessage());
            System.err.println("[runtime] install failed: " + ex);
        } finally {
            if (staging != null) {
                try {
                    deleteRecursive(staging);
                } catch (IOException ignored) {
                }
            }
            if (archivePath != null) {
                try {
                    Files.deleteIfExists(archivePath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Map<String, Object> resolveHuggingFace(Map<String, Object> entry) throws Exception {
        Exception last = null;
        for (String base : modelBaseCandidates()) {
            try {
                Map<String, Object> meta = resolveHuggingFace(base, entry);
                meta.put("source", base);
                return meta;
            } catch (Exception ex) {
                last = ex;
                System.err.println("[models] metadata from " + base + " failed: "
                        + ex.getMessage());
            }
        }
        throw last != null ? last : new IOException("No model metadata source is configured.");
    }

    private List<String> modelBaseCandidates() {
        String configured = System.getenv("CODEFORGE_MODEL_MIRROR");
        if (configured == null || configured.isBlank()) {
            try {
                Map<String, Object> doc = Json.asObject(Json.parse(Files.readString(
                        root.resolve("config.json"), StandardCharsets.UTF_8)));
                configured = Json.str(Json.asObject(doc.get("llm")), "model_mirror", "");
            } catch (Exception ignored) {
            }
        }
        List<String> bases = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            for (String part : configured.split("[,\\s]+")) {
                String base = part.trim().replaceAll("/+$", "");
                if (!base.isEmpty() && !bases.contains(base)) {
                    bases.add(base);
                }
            }
        }
        if (bases.isEmpty()) {
            bases.add(DEFAULT_MODEL_MIRROR);
        }
        if (!bases.contains(MODEL_SOURCE_FALLBACK)) {
            bases.add(MODEL_SOURCE_FALLBACK);
        }
        return bases;
    }

    private Map<String, Object> resolveHuggingFace(String base, Map<String, Object> entry)
            throws Exception {
        String repo = Json.str(entry, "repo", "");
        String file = Json.str(entry, "file", "");
        String api = base + "/api/models/" + repo + "?blobs=true";
        Map<String, Object> doc = Json.asObject(Json.parse(httpGetText(api)));
        List<Object> siblings = Json.asList(doc.get("siblings"));
        long size = 0L;
        String sha = "";
        boolean found = false;
        for (Object item : siblings) {
            Map<String, Object> sibling = Json.asObject(item);
            if (!file.equals(Json.str(sibling, "rfilename", ""))) {
                continue;
            }
            found = true;
            size = Json.lon(sibling, "size", 0L);
            Object lfsObject = sibling.get("lfs");
            Map<String, Object> lfs = Json.asObject(lfsObject);
            if (!lfs.isEmpty()) {
                size = Json.lon(lfs, "size", size);
                sha = Json.str(lfs, "sha256", Json.str(lfs, "oid", sha));
            }
            break;
        }
        if (!found) {
            throw new IOException("File not found in the Hugging Face repository at " + base
                    + ": " + file);
        }
        if (size <= 0L || sha.isBlank()) {
            throw new IOException("Metadata from " + base
                    + " is missing size or SHA-256 for " + file);
        }
        String url = base + "/" + repo + "/resolve/main/" + file + "?download=true";
        return Json.map("size", size, "sha256", sha, "url", url);
    }

    private Map<String, Object> resolveLlamaRuntime() throws Exception {
        if (RUNTIME_PATTERNS.isEmpty()) {
            throw new IOException("No llama.cpp CPU release is published for " + ARCH + ". "
                    + runtimeHint());
        }
        String api = "https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=10";
        Object parsed = Json.parse(httpGetText(api));
        List<Object> releases = Json.asList(parsed);
        for (Object releaseObject : releases) {
            Map<String, Object> release = Json.asObject(releaseObject);
            List<Object> assets = Json.asList(release.get("assets"));
            for (int tagIndex = 0; tagIndex < RUNTIME_TAGS.size(); ++tagIndex) {
                for (Object assetObject : assets) {
                    Map<String, Object> asset = Json.asObject(assetObject);
                    String name = Json.str(asset, "name", "");
                    if (!RUNTIME_PATTERNS.get(tagIndex).matcher(name).matches()) {
                        continue;
                    }
                    return Json.map(
                            "tag", Json.str(release, "tag_name", ""),
                            "name", name,
                            "platform", RUNTIME_TAGS.get(tagIndex),
                            "size", Json.lon(asset, "size", 0L),
                            "digest", Json.str(asset, "digest", ""),
                            "url", Json.str(asset, "browser_download_url", ""));
                }
            }
        }
        throw new IOException("No llama.cpp CPU release found for " + runtimeLabel()
                + ". " + runtimeHint());
    }

    private String downloadTo(String url, Path part, Job job,
                              long expectedSize, String expectedSha) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= 12; attempt++) {
            try {
                return downloadAttempt(url, part, job, expectedSize, expectedSha);
            } catch (IOException ex) {
                last = ex;
                if (Thread.currentThread().isInterrupted()) {
                    throw ex;
                }
                long have = Files.isRegularFile(part) ? Files.size(part) : 0L;
                long percent = expectedSize > 0 ? have * 100L / expectedSize : 0L;
                job.setStatus("downloading", "Connection dropped (" + ex.getMessage() +
                        ") — resuming at " + percent + "%, attempt " + attempt + "/12");
                job.progress(have, expectedSize > 0 ? expectedSize : have);
                Thread.sleep(Math.min(20000L, 2000L * attempt));
            }
        }
        throw last;
    }

    private String downloadAttempt(String url, Path part, Job job,
                                   long expectedSize, String expectedSha) throws Exception {
        Files.createDirectories(part.getParent());
        long offset = Files.isRegularFile(part) ? Files.size(part) : 0L;

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofHours(8))
                .header("User-Agent", "CodeForge-AI/1.0")
                .header("Accept", "application/octet-stream")
                .GET();
        if (offset > 0) {
            builder.header("Range", "bytes=" + offset + "-");
        }
        HttpResponse<InputStream> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        boolean append;
        long total = expectedSize;
        if (status == 206) {
            append = true;
            String contentRange = response.headers().firstValue("Content-Range").orElse("");
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    total = Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (status == 200) {
            append = false;
            offset = 0;
        } else if (status == 416 && expectedSize > 0 && offset >= expectedSize) {
            response.body().close();
            append = true;
        } else {
            response.body().close();
            throw new IOException("HTTP " + status + " while downloading " + url);
        }

        if (total <= 0) {
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (length > 0) {
                total = offset + length;
            }
        }
        job.setTotal(total);
        job.progress(offset, total);

        if (!(status == 416)) {
            StandardOpenOption[] options = append
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                             StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                             StandardOpenOption.TRUNCATE_EXISTING};
            long downloaded = offset;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(part, options)) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                long lastReport = 0L;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 250L) {
                        job.progress(downloaded, total);
                        lastReport = now;
                    }
                }
                job.progress(downloaded, total);
            }
        }

        long actual = Files.size(part);
        if (expectedSize > 0 && actual < expectedSize) {
            throw new IOException("Incomplete download (" + actual + "/" + expectedSize +
                                  " bytes). Partial file kept for resume.");
        }
        if (expectedSize > 0 && actual > expectedSize) {
            Files.deleteIfExists(part);
            throw new IOException("Downloaded file has an unexpected size and was removed. Please retry.");
        }

        job.setStatus("verifying", "Verifying SHA-256...");
        NativeTools.HashResult hash = nativeTools.verify(part, expectedSha);
        if (!hash.ok) {
            throw new IOException("SHA-256 verification failed: " + hash.error);
        }
        if (!hash.match) {
            Files.deleteIfExists(part);
            throw new IOException("SHA-256 mismatch. The file was removed; please download it again.");
        }
        return hash.sha256;
    }

    private void ensureDiskSpace(long expectedSize) throws IOException {
        if (expectedSize <= 0) {
            return;
        }
        FileStore store = Files.getFileStore(root);
        long usable = store.getUsableSpace();
        long required = expectedSize + 512L * 1024L * 1024L;
        if (usable < required) {
            throw new IOException("Not enough disk space: about " + (required / 1024 / 1024) +
                                  " MB required, " + (usable / 1024 / 1024) + " MB");
        }
    }

    private String httpGetText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "CodeForge-AI/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private static void extractArchive(Path archive, Path target) throws IOException {
        String name = archive.getFileName().toString().toLowerCase();
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, target);
        } else {
            extractZip(archive, target);
        }
    }

    private static void extractTarGz(Path archive, Path target) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(raw))) {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(gzip, header);
                if (read == 0) {
                    break;
                }
                if (read < header.length) {
                    throw new IOException("Truncated tar header");
                }
                boolean empty = true;
                for (byte value : header) {
                    if (value != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty) {
                    break;
                }
                String name = tarString(header, 0, 100);
                String prefix = tarString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = tarOctal(header, 124, 12);
                int type = header[156] & 0xFF;
                Path destination = target.resolve(name).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Illegal tar path: " + name);
                }
                if (type == '5') {
                    Files.createDirectories(destination);
                } else if (type == 0 || type == '0') {
                    Files.createDirectories(destination.getParent());
                    try (OutputStream output = Files.newOutputStream(destination)) {
                        copyExactly(gzip, output, size);
                    }
                } else {
                    skipExactly(gzip, size);
                }
                skipExactly(gzip, (512 - (size % 512)) % 512);
            }
        }
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            ++end;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long tarOctal(byte[] header, int offset, int length) throws IOException {
        String text = tarString(header, offset, length).replace("\u0000", "").trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException ex) {
            throw new IOException("Illegal tar size: " + text);
        }
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static void copyExactly(InputStream input, OutputStream output, long size)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated tar data");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(InputStream input, long size) throws IOException {
        byte[] buffer = new byte[4096];
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated tar data");
            }
            remaining -= read;
        }
    }

    private static void extractZip(Path zipPath, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path destination = target.resolve(entry.getName()).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Illegal archive path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Path findFile(Path directory, String fileName) throws IOException {
        try (var stream = Files.walk(directory, 6)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static final class Job {
        final String id;
        final String kind;
        final String modelId;
        final String modelName;
        final long startedAt = System.currentTimeMillis();
        volatile String status = "queued";
        volatile String message = "Waiting to start";
        volatile long downloaded = 0L;
        volatile long total = 0L;
        volatile double speed = 0.0;
        volatile long updatedAt = System.currentTimeMillis();
        private long lastTime = System.currentTimeMillis();
        private long lastBytes = 0L;

        Job(String id, String kind, String modelId, String modelName) {
            this.id = id;
            this.kind = kind;
            this.modelId = modelId;
            this.modelName = modelName;
        }

        boolean isActive() {
            return status.equals("queued") || status.equals("resolving") ||
                   status.equals("downloading") || status.equals("verifying") ||
                   status.equals("extracting");
        }

        synchronized void setStatus(String status, String message) {
            this.status = status;
            this.message = message;
            this.updatedAt = System.currentTimeMillis();
        }

        synchronized void setTotal(long total) {
            this.total = Math.max(0L, total);
            this.updatedAt = System.currentTimeMillis();
        }

        synchronized void progress(long downloaded, long total) {
            long now = System.currentTimeMillis();
            this.downloaded = Math.max(0L, downloaded);
            if (total > 0) {
                this.total = total;
            }
            long elapsed = now - lastTime;
            if (elapsed >= 500L) {
                double instant = (downloaded - lastBytes) * 1000.0 / elapsed;
                speed = speed <= 0.0 ? instant : speed * 0.7 + instant * 0.3;
                lastTime = now;
                lastBytes = downloaded;
            }
            this.updatedAt = now;
        }

        synchronized void complete(String message) {
            this.status = "complete";
            this.message = message;
            this.speed = 0.0;
            this.updatedAt = System.currentTimeMillis();
        }

        synchronized void fail(String message) {
            this.status = "error";
            this.message = message;
            this.speed = 0.0;
            this.updatedAt = System.currentTimeMillis();
        }

        synchronized Map<String, Object> toMap() {
            double percent = total > 0
                    ? Math.min(100.0, Math.max(0.0, downloaded * 100.0 / total)) : 0.0;
            return Json.map(
                    "id", id,
                    "kind", kind,
                    "model_id", modelId,
                    "model_name", modelName,
                    "status", status,
                    "message", message,
                    "downloaded", downloaded,
                    "total", total,
                    "percent", Math.round(percent * 10.0) / 10.0,
                    "speed", Math.round(speed),
                    "started_at", startedAt,
                    "updated_at", updatedAt);
        }
    }
}
