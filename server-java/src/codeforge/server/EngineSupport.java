package codeforge.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Adapter layer for installed game engines. CodeForge never downloads or
 * bundles an engine: it detects the user's installation, reads project
 * markers, builds the engine's own command line and returns real output.
 */
public final class EngineSupport {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long STATUS_TTL_MS = 60_000;
    private static final long MAX_READ = 512L * 1024;
    private static final long MAX_LOVE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_LOVE_FILES = 20_000;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "__pycache__", ".venv", "Library", "Temp", "Logs",
            "Intermediate", "Saved", "DerivedDataCache", "Binaries", "Build", "build",
            ".gradle", ".idea", ".vs", "obj", "packages", "target");

    private static final class Spec {
        final String id;
        final String name;
        final List<String> exeNames;
        final List<String> versionArgs;
        final List<String> actions;
        final String installHint;
        final String license;

        Spec(String id, String name, List<String> exeNames, List<String> versionArgs,
             List<String> actions, String installHint, String license) {
            this.id = id;
            this.name = name;
            this.exeNames = exeNames;
            this.versionArgs = versionArgs;
            this.actions = actions;
            this.installHint = installHint;
            this.license = license;
        }
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("godot", "Godot 4",
                    List.of("godot", "godot4", "Godot", "Godot_v4"),
                    List.of("--version"), List.of("build", "export", "run"),
                    "Install Godot 4 and add the executable to PATH, or set GODOT_BIN "
                            + "to the Godot executable.",
                    "MIT; engine not bundled"),
            new Spec("unity", "Unity",
                    List.of("Unity", "Unity.exe"),
                    List.of("-version"), List.of("build", "export", "run"),
                    "Install Unity Hub and a Unity Editor, or set UNITY_EDITOR to the "
                            + "Unity executable.",
                    "Proprietary; user-installed editor required"),
            new Spec("unreal", "Unreal Engine 5",
                    List.of("UnrealEditor", "UnrealEditor.exe"),
                    List.of(), List.of("build", "export", "run"),
                    "Install Unreal Engine 5 and set UE_ROOT to the installed UE_5.x "
                            + "folder (the one containing Engine/).",
                    "EULA; user-installed engine required"),
            new Spec("cocos", "Cocos Creator",
                    List.of("CocosCreator", "CocosCreator.exe"),
                    List.of("--version"), List.of("build", "export", "run"),
                    "Install Cocos Creator 3.x, or set COCOS_CREATOR to the CocosCreator "
                            + "executable.",
                    "Proprietary; user-installed editor required"),
            new Spec("love", "LÖVE",
                    List.of("love", "love.exe"),
                    List.of("--version"), List.of("build", "export", "run"),
                    "Install LÖVE 11 and add love to PATH, or set LOVE_BIN to the love "
                            + "executable.",
                    "zlib; engine not bundled"));

    public static final class EngineInfo {
        final Spec spec;
        boolean installed;
        Path executable;
        Path engineRoot;
        String version = "";
        String source = "";
        String note = "";

        EngineInfo(Spec spec) {
            this.spec = spec;
        }

        Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", spec.id);
            out.put("name", spec.name);
            out.put("installed", installed);
            out.put("executable", executable == null ? "" : executable.toString());
            out.put("engine_root", engineRoot == null ? "" : engineRoot.toString());
            out.put("version", version);
            out.put("source", source);
            out.put("actions", spec.actions);
            out.put("install_hint", spec.installHint);
            out.put("license", spec.license);
            out.put("capabilities", Json.map(
                    "build", spec.actions.contains("build"),
                    "export", spec.actions.contains("export"),
                    "run", spec.actions.contains("run"),
                    "bundled", false));
            if (!note.isBlank()) {
                out.put("note", note);
            }
            return out;
        }
    }

    private static final class Project {
        String engine;
        String name;
        Path path;
        String version = "";
        List<String> markers = new ArrayList<>();
        List<Map<String, Object>> presets = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("engine", engine);
            out.put("name", name);
            out.put("path", path.toString());
            out.put("version", version);
            out.put("markers", markers);
            out.put("presets", presets);
            out.put("targets", targets);
            out.put("warnings", warnings);
            out.putAll(extra);
            return out;
        }
    }

    private final Path root;
    private final Path workspace;
    private final Map<String, String> env;
    private final Path home;
    private Map<String, EngineInfo> cachedEngines;
    private long cachedAt;
    private long cachedDuration;

    public EngineSupport(Path root) {
        this(root, System.getenv());
    }

    public EngineSupport(Path root, Map<String, String> environment) {
        this.root = root;
        this.workspace = root.resolve("workspace");
        this.env = environment == null ? Map.of() : environment;
        String userHome = firstNonBlank(env.get("USERPROFILE"), env.get("HOME"),
                System.getProperty("user.home", ""));
        this.home = userHome.isBlank() ? Path.of(".") : Path.of(userHome);
    }

    public Map<String, Object> status(boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && cachedEngines != null && now - cachedAt < STATUS_TTL_MS) {
            return statusView(cachedEngines, now - cachedAt);
        }
        Map<String, EngineInfo> engines = new LinkedHashMap<>();
        for (Spec spec : SPECS) {
            engines.put(spec.id, detect(spec));
        }
        cachedEngines = engines;
        cachedAt = now;
        return statusView(engines, 0);
    }

    private Map<String, Object> statusView(Map<String, EngineInfo> engines, long age) {
        List<Object> list = new ArrayList<>();
        int installed = 0;
        for (EngineInfo info : engines.values()) {
            list.add(info.describe());
            if (info.installed) {
                ++installed;
            }
        }
        return Json.map(
                "ok", true,
                "engine_not_bundled", true,
                "engines", list,
                "count", list.size(),
                "installed", installed,
                "cache_age_ms", age,
                "cache_seconds", STATUS_TTL_MS / 1000,
                "checked_at", LocalDateTime.now().toString(),
                "note", "Adapters call engines already installed on this machine. "
                        + "CodeForge never downloads, bundles or redistributes an engine.");
    }

    public Map<String, Object> handle(Map<String, Object> request) {
        String action = Json.str(request, "action", "detect").trim().toLowerCase(Locale.ROOT);
        if (action.isBlank() || "status".equals(action)) {
            return status(true);
        }
        if ("detect".equals(action) || "scan".equals(action)) {
            String path = Json.str(request, "path", "").trim();
            int depth = Math.max(0, Math.min(4, Json.integer(request, "depth", 2)));
            return detectProjects(path, depth);
        }
        long started = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("tool", "codeforge_engine");
        out.put("action", action);
        out.put("phase", "setup");
        try {
            if (!List.of("build", "export", "run").contains(action)) {
                throw new Failure("Unknown engine action: " + action
                        + ". Use detect, build, export or run.", null);
            }
            String rawPath = firstNonBlank(Json.str(request, "path", ""),
                    Json.str(request, "project", ""), Json.str(request, "source", ""));
            Project project = resolveProject(rawPath, Json.str(request, "engine", ""));
            out.put("engine", project.engine);
            out.put("project", project.name);
            out.put("project_path", project.path.toString());
            out.put("project_version", project.version);
            out.put("presets", project.presets);
            out.put("targets", project.targets);
            EngineInfo engine = resolveEngine(project.engine, true);
            if (engine == null) {
                throw new Failure("Unsupported engine: " + project.engine, null);
            }
            if ("build".equals(action) && "love".equals(project.engine)) {
                Path output = resolveOutput(request, project);
                Path artifact = packLove(project, output);
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("artifact", artifact.toString());
                out.put("artifact_kind", "love-package");
                out.put("output", output.toString());
                out.put("message", "Created " + artifact.getFileName()
                        + ". Use export for a fused Windows executable.");
                out.put("duration_ms", System.currentTimeMillis() - started);
                return out;
            }
            if (!engine.installed) {
                throw new Failure(engine.spec.name + " is not installed. "
                        + engine.spec.installHint, null);
            }
            out.put("engine_name", engine.spec.name);
            out.put("engine_version", engine.version);
            out.put("engine_executable", engine.executable.toString());
            if ("build".equals(action)) {
                build(project, engine, request, out);
            } else if ("export".equals(action)) {
                export(project, engine, request, out);
            } else {
                run(project, engine, request, out);
            }
            out.put("duration_ms", System.currentTimeMillis() - started);
        } catch (Failure ex) {
            out.put("ok", false);
            out.put("phase", "failed");
            out.put("error", ex.getMessage());
            if (ex.exec != null) {
                putExec(out, ex.exec);
            }
            String engineId = Json.str(out, "engine", "");
            EngineInfo info = engineId.isBlank() ? null : resolveEngine(engineId, false);
            if (info != null) {
                out.put("install_hint", info.spec.installHint);
                out.put("engine_not_bundled", true);
            }
            out.put("duration_ms", System.currentTimeMillis() - started);
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("phase", "failed");
            out.put("error", ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
            out.put("duration_ms", System.currentTimeMillis() - started);
        }
        return out;
    }

    public Map<String, Object> detectProjects(String rawPath, int depth) {
        long started = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "codeforge_engine");
        out.put("action", "detect");
        if (rawPath == null || rawPath.isBlank()) {
            out.put("ok", false);
            out.put("error", "A project folder or engine project file is required.");
            out.put("count", 0);
            out.put("projects", List.of());
            return out;
        }
        try {
            Path base = resolveExisting(rawPath);
            List<Project> projects = findProjects(base, depth);
            List<Object> list = new ArrayList<>();
            for (Project project : projects) {
                Map<String, Object> item = project.describe();
                EngineInfo info = resolveEngine(project.engine, false);
                if (info != null) {
                    item.put("engine_installed", info.installed);
                    item.put("install_hint", info.spec.installHint);
                    item.put("engine_version", info.version);
                }
                list.add(item);
            }
            out.put("path", base.toString());
            out.put("count", list.size());
            out.put("projects", list);
            out.put("searched_depth", depth);
            out.put("engine_not_bundled", true);
            if (list.isEmpty()) {
                out.put("message", "No Godot, Unity, Unreal, Cocos Creator or LÖVE project "
                        + "markers were found at this path.");
            }
        } catch (Failure ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage());
            out.put("count", 0);
            out.put("projects", List.of());
        }
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* detection                                                          */
    /* ------------------------------------------------------------------ */

    private EngineInfo detect(Spec spec) {
        return switch (spec.id) {
            case "godot" -> detectGodot(spec);
            case "unity" -> detectUnity(spec);
            case "unreal" -> detectUnreal(spec);
            case "cocos" -> detectCocos(spec);
            case "love" -> detectLove(spec);
            default -> new EngineInfo(spec);
        };
    }

    private EngineInfo detectGodot(Spec spec) {
        EngineInfo info = new EngineInfo(spec);
        Path exe = envFile("GODOT_BIN");
        if (exe == null) {
            exe = which(spec.exeNames);
        }
        if (exe == null) {
            exe = firstFile(
                    programFiles("Godot", "Godot.exe"),
                    localAppData("Programs", "Godot", "Godot.exe"),
                    home.resolve("Godot").resolve("Godot.exe"));
        }
        install(info, exe, spec.versionArgs);
        if (info.installed) {
            info.engineRoot = info.executable.getParent();
        }
        return info;
    }

    private EngineInfo detectUnity(Spec spec) {
        EngineInfo info = new EngineInfo(spec);
        Path exe = envFile("UNITY_EDITOR");
        if (exe == null) {
            exe = which(List.of("Unity", "Unity.exe"));
        }
        if (exe == null) {
            exe = newestChild(programFiles("Unity", "Hub", "Editor"),
                    WINDOWS ? new String[]{"Editor", "Unity.exe"}
                            : new String[]{"Editor", "Unity"});
        }
        if (exe == null && MAC) {
            exe = newestChild(Path.of("/Applications/Unity/Hub/Editor"),
                    new String[]{"Unity.app", "Contents", "MacOS", "Unity"});
        }
        if (exe == null) {
            exe = newestChild(home.resolve("Unity/Hub/Editor"),
                    new String[]{"Editor", "Unity"});
        }
        install(info, exe, spec.versionArgs);
        if (info.installed) {
            info.engineRoot = info.executable.getParent();
        }
        return info;
    }

    private EngineInfo detectUnreal(Spec spec) {
        EngineInfo info = new EngineInfo(spec);
        Path engineRoot = null;
        for (String key : new String[]{"UE_ROOT", "UNREAL_ENGINE_ROOT", "UE5_ROOT"}) {
            Path raw = pathFromEnv(key);
            if (raw == null) {
                continue;
            }
            if (Files.isRegularFile(raw)) {
                Path parent = raw.getParent();
                engineRoot = guessUnrealEngineRoot(parent);
            } else {
                engineRoot = guessUnrealEngineRoot(raw);
            }
            if (engineRoot != null) {
                info.source = key;
                break;
            }
        }
        if (engineRoot == null) {
            List<Path> roots = new ArrayList<>();
            roots.add(programFiles("Epic Games"));
            if (MAC) {
                roots.add(Path.of("/Users/Shared/Epic Games"));
            }
            roots.add(home.resolve("UnrealEngine"));
            for (Path rootCandidate : roots) {
                engineRoot = newestUnreal(rootCandidate);
                if (engineRoot != null) {
                    info.source = "common";
                    break;
                }
            }
        }
        if (engineRoot != null) {
            Path exe = unrealEditor(engineRoot);
            if (exe != null) {
                info.installed = true;
                info.executable = exe;
                info.engineRoot = engineRoot;
                info.source = info.source.isBlank() ? "common" : info.source;
                info.version = unrealVersion(engineRoot);
            }
        }
        return info;
    }

    private EngineInfo detectCocos(Spec spec) {
        EngineInfo info = new EngineInfo(spec);
        Path exe = envFile("COCOS_CREATOR");
        if (exe == null) {
            exe = envFile("COCOS_CREATOR_PATH");
        }
        if (exe == null) {
            exe = which(spec.exeNames);
        }
        if (exe == null) {
            exe = firstFile(
                    programFiles("CocosCreator", "CocosCreator.exe"),
                    localAppData("Programs", "CocosCreator", "CocosCreator.exe"));
        }
        if (exe == null && MAC) {
            Path app = Path.of("/Applications/CocosCreator.app/Contents/MacOS/CocosCreator");
            exe = Files.isRegularFile(app) ? app : null;
        }
        install(info, exe, spec.versionArgs);
        if (info.installed) {
            info.engineRoot = info.executable.getParent();
            info.version = info.version.isBlank() ? "Cocos Creator" : info.version;
        }
        return info;
    }

    private EngineInfo detectLove(Spec spec) {
        EngineInfo info = new EngineInfo(spec);
        Path exe = envFile("LOVE_BIN");
        if (exe == null) {
            exe = which(spec.exeNames);
        }
        if (exe == null) {
            exe = firstFile(
                    programFiles("LOVE", "love.exe"),
                    programFiles("LOVE", "love"),
                    programFilesX86("LOVE", "love.exe"),
                    localAppData("Programs", "LOVE", "love.exe"));
        }
        if (exe == null && MAC) {
            Path app = Path.of("/Applications/love.app/Contents/MacOS/love");
            exe = Files.isRegularFile(app) ? app : null;
        }
        if (exe == null && !WINDOWS && !MAC) {
            Path app = Path.of("/usr/bin/love");
            exe = Files.isRegularFile(app) ? app : null;
        }
        install(info, exe, spec.versionArgs);
        if (info.installed) {
            info.engineRoot = info.executable.getParent();
        }
        return info;
    }

    private void install(EngineInfo info, Path exe, List<String> versionArgs) {
        if (exe == null || !Files.isRegularFile(exe)) {
            return;
        }
        info.installed = true;
        info.executable = exe;
        info.source = info.source.isBlank() ? "detected" : info.source;
        info.version = probeVersion(exe, versionArgs);
    }

    private EngineInfo resolveEngine(String id, boolean refresh) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (refresh || cachedEngines == null || now - cachedAt >= STATUS_TTL_MS) {
            status(refresh);
        }
        if (cachedEngines != null) {
            return cachedEngines.get(wanted);
        }
        for (Spec spec : SPECS) {
            if (spec.id.equals(wanted)) {
                return detect(spec);
            }
        }
        return null;
    }

    private Path envFile(String key) {
        Path raw = pathFromEnv(key);
        if (raw == null) {
            return null;
        }
        if (Files.isRegularFile(raw)) {
            return raw;
        }
        if (Files.isDirectory(raw)) {
            for (String name : new String[]{"godot.exe", "Godot.exe", "love.exe",
                    "CocosCreator.exe", "Unity.exe"}) {
                Path candidate = raw.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Path pathFromEnv(String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private Path which(List<String> names) {
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String piece : path.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (piece.isBlank()) {
                continue;
            }
            Path dir;
            try {
                dir = Path.of(piece);
            } catch (Exception ex) {
                continue;
            }
            for (String name : names) {
                for (String candidate : new String[]{name, name + ".exe",
                        name + ".cmd", name + ".bat"}) {
                    Path file = dir.resolve(candidate);
                    if (Files.isRegularFile(file)) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    private Path programFiles(String... parts) {
        String base = firstNonBlank(env.get("ProgramFiles"), env.get("PROGRAMFILES"),
                "C:\\Program Files");
        return resolve(base, parts);
    }

    private Path programFilesX86(String... parts) {
        String base = firstNonBlank(env.get("ProgramFiles(x86)"),
                env.get("PROGRAMFILES(X86)"), "C:\\Program Files (x86)");
        return resolve(base, parts);
    }

    private Path localAppData(String... parts) {
        String base = firstNonBlank(env.get("LOCALAPPDATA"),
                home.resolve("AppData/Local").toString());
        return resolve(base, parts);
    }

    private static Path resolve(String base, String... parts) {
        Path path = Path.of(base);
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    private static Path firstFile(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path newestChild(Path parent, String[] tail) {
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path item : stream) {
                if (Files.isDirectory(item)) {
                    dirs.add(item);
                }
            }
        } catch (IOException ex) {
            return null;
        }
        dirs.sort(Comparator.comparing((Path p) -> p.getFileName().toString())
                .reversed());
        for (Path dir : dirs) {
            Path candidate = dir;
            for (String part : tail) {
                candidate = candidate.resolve(part);
            }
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (MAC && "MacOS".equals(tail[tail.length - 2])) {
                Path app = dir.resolve("Unity.app/Contents/MacOS/Unity");
                if (Files.isRegularFile(app)) {
                    return app;
                }
            }
        }
        return null;
    }

    private static Path guessUnrealEngineRoot(Path path) {
        if (path == null) {
            return null;
        }
        Path current = path;
        for (int i = 0; i < 4 && current != null; ++i) {
            Path engine = current.getFileName() != null
                    && "Engine".equalsIgnoreCase(current.getFileName().toString())
                    ? current : current.resolve("Engine");
            if (Files.isDirectory(engine.resolve("Build"))) {
                return engine;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path newestUnreal(Path base) {
        if (base == null || !Files.isDirectory(base)) {
            return null;
        }
        Path direct = guessUnrealEngineRoot(base);
        if (direct != null) {
            return direct;
        }
        List<Path> roots = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path item : stream) {
                String name = item.getFileName() == null ? ""
                        : item.getFileName().toString();
                if (Files.isDirectory(item) && name.toUpperCase(Locale.ROOT).startsWith("UE_")) {
                    roots.add(item);
                }
            }
        } catch (IOException ex) {
            return null;
        }
        roots.sort(Comparator.comparing((Path p) -> p.getFileName().toString())
                .reversed());
        for (Path root : roots) {
            Path engine = guessUnrealEngineRoot(root);
            if (engine != null) {
                return engine;
            }
        }
        return null;
    }

    private static Path unrealEditor(Path engineRoot) {
        if (engineRoot == null) {
            return null;
        }
        return firstFile(
                engineRoot.resolve("Binaries/Win64/UnrealEditor.exe"),
                engineRoot.resolve("Binaries/Mac/UnrealEditor.app/Contents/MacOS/UnrealEditor"),
                engineRoot.resolve("Binaries/Linux/UnrealEditor"));
    }

    private static String unrealVersion(Path engineRoot) {
        Path build = engineRoot.resolve("Build/Build.version");
        try {
            if (Files.isRegularFile(build)) {
                Map<String, Object> doc = Json.asObject(Json.parse(
                        Files.readString(build, StandardCharsets.UTF_8)));
                String version = Json.integer(doc, "MajorVersion", 0) + "."
                        + Json.integer(doc, "MinorVersion", 0) + "."
                        + Json.integer(doc, "PatchVersion", 0);
                if (!version.equals("0.0.0")) {
                    return version;
                }
            }
        } catch (Exception ignored) {
        }
        Path parent = engineRoot.getParent();
        return parent == null || parent.getFileName() == null ? "Unreal Engine"
                : parent.getFileName().toString();
    }

    /* ------------------------------------------------------------------ */
    /* projects                                                           */
    /* ------------------------------------------------------------------ */

    private Project resolveProject(String rawPath, String engineFilter) throws Failure {
        if (rawPath == null || rawPath.isBlank()) {
            throw new Failure("A project folder or engine project file is required.", null);
        }
        Path base = resolveExisting(rawPath);
        List<Project> projects = findProjects(base, Files.isDirectory(base) ? 2 : 0);
        if (projects.isEmpty()) {
            throw new Failure("No supported engine project was found at " + base
                    + ". Looked for project.godot, ProjectSettings/ProjectVersion.txt, "
                    + "*.uproject, Cocos package.json/project.json and main.lua.", null);
        }
        String wanted = engineFilter == null ? "" : engineFilter.trim().toLowerCase(Locale.ROOT);
        for (Project project : projects) {
            if (wanted.isBlank() || project.engine.equals(wanted)) {
                return project;
            }
        }
        throw new Failure("No " + engineFilter + " project was found at " + base
                + ". Detected: " + projects.stream().map(p -> p.engine)
                .reduce((a, b) -> a + ", " + b).orElse("none"), null);
    }

    private List<Project> findProjects(Path base, int depth) {
        List<Project> out = new ArrayList<>();
        Path resolved = base;
        if (Files.isRegularFile(resolved)) {
            resolved = resolved.getParent();
            if (resolved == null) {
                return out;
            }
        }
        final Path start = resolved;
        try (Stream<Path> walk = Files.walk(start, Math.max(0, Math.min(4, depth)))) {
            List<Path> dirs = walk.filter(Files::isDirectory)
                    .filter(path -> !skipped(path, start))
                    .limit(4000)
                    .toList();
            for (Path dir : dirs) {
                Project project = match(dir);
                if (project != null) {
                    out.add(project);
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing((Project p) -> p.path.getNameCount())
                .thenComparing(p -> p.path.toString()));
        return out;
    }

    private static boolean skipped(Path path, Path base) {
        if (path.equals(base)) {
            return false;
        }
        for (Path part : base.relativize(path)) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private Project match(Path dir) {
        Project project = null;
        if (Files.isRegularFile(dir.resolve("project.godot"))) {
            project = godotProject(dir);
        }
        if (project == null && Files.isRegularFile(dir.resolve("ProjectSettings/ProjectVersion.txt"))
                && Files.isDirectory(dir.resolve("Assets"))) {
            project = unityProject(dir);
        }
        if (project == null) {
            Path uproject = firstByExtension(dir, ".uproject");
            if (uproject != null) {
                project = unrealProject(dir, uproject);
            }
        }
        if (project == null) {
            project = cocosProject(dir);
        }
        if (project == null && Files.isRegularFile(dir.resolve("main.lua"))) {
            project = loveProject(dir);
        }
        return project;
    }

    private Project godotProject(Path dir) {
        Project project = baseProject("godot", dir);
        project.markers.add("project.godot");
        String text = readSmall(dir.resolve("project.godot"));
        Matcher name = Pattern.compile("(?m)^config/name\\s*=\\s*\"([^\"]*)\"")
                .matcher(text);
        if (name.find() && !name.group(1).isBlank()) {
            project.name = name.group(1).trim();
        }
        Matcher features = Pattern.compile(
                "config/features\\s*=\\s*PackedStringArray\\(([^)]*)\\)").matcher(text);
        if (features.find()) {
            Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(features.group(1));
            if (quoted.find()) {
                project.version = quoted.group(1);
            }
        }
        project.presets = godotPresets(dir.resolve("export_presets.cfg"));
        project.targets = List.of("windows", "linux", "macos", "web");
        if (project.presets.isEmpty()) {
            project.warnings.add("No export_presets.cfg preset found. Create an export "
                    + "preset in the Godot editor before exporting.");
        }
        return project;
    }

    private static List<Map<String, Object>> godotPresets(Path file) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        String text = readSmall(file);
        Pattern block = Pattern.compile("(?ms)^\\[preset\\.(\\d+)\\]\\s*\\R(.*?)(?=^\\[|\\z)");
        Matcher matcher = block.matcher(text);
        while (matcher.find()) {
            String body = matcher.group(2);
            String name = match(body, "(?m)^name\\s*=\\s*\"([^\"]*)\"");
            String platform = match(body, "(?m)^platform\\s*=\\s*\"([^\"]*)\"");
            String exportPath = match(body, "(?m)^export_path\\s*=\\s*\"([^\"]*)\"");
            Map<String, Object> preset = new LinkedHashMap<>();
            preset.put("id", matcher.group(1));
            preset.put("name", name.isBlank() ? "preset-" + matcher.group(1) : name);
            preset.put("platform", platform);
            preset.put("export_path", exportPath);
            out.add(preset);
        }
        return out;
    }

    private Project unityProject(Path dir) {
        Project project = baseProject("unity", dir);
        project.markers.add("Assets/");
        project.markers.add("ProjectSettings/ProjectVersion.txt");
        String text = readSmall(dir.resolve("ProjectSettings/ProjectVersion.txt"));
        String version = match(text, "(?m)^m_EditorVersion:\\s*(\\S+)");
        project.version = version;
        project.targets = List.of("windows", "linux", "macos");
        if (version.isBlank()) {
            project.warnings.add("ProjectVersion.txt has no m_EditorVersion line.");
        }
        return project;
    }

    private Project unrealProject(Path dir, Path uproject) {
        Project project = baseProject("unreal", dir);
        project.name = stripExtension(uproject.getFileName().toString());
        project.markers.add(uproject.getFileName().toString());
        String text = readSmall(uproject);
        try {
            Map<String, Object> doc = Json.asObject(Json.parse(text));
            String association = Json.str(doc, "EngineAssociation", "");
            project.version = association;
            Object modules = doc.get("Modules");
            if (modules != null) {
                project.extra.put("modules", modules);
            }
        } catch (Exception ex) {
            project.warnings.add("Could not parse the .uproject JSON: " + ex.getMessage());
        }
        project.targets = List.of("windows", "linux", "macos");
        return project;
    }

    private Project cocosProject(Path dir) {
        Path packageJson = dir.resolve("package.json");
        Path projectJson = dir.resolve("project.json");
        if (!Files.isRegularFile(packageJson) && !Files.isRegularFile(projectJson)) {
            return null;
        }
        if (!Files.isDirectory(dir.resolve("assets"))) {
            return null;
        }
        String text = Files.isRegularFile(packageJson)
                ? readSmall(packageJson) : readSmall(projectJson);
        boolean cocos = text.contains("cocos") || text.contains("creator");
        if (!cocos) {
            return null;
        }
        Project project = baseProject("cocos", dir);
        project.markers.add(Files.isRegularFile(packageJson) ? "package.json" : "project.json");
        project.markers.add("assets/");
        try {
            Map<String, Object> doc = Json.asObject(Json.parse(text));
            Map<String, Object> creator = Json.asObject(doc.get("creator"));
            String version = Json.str(creator, "version", "");
            if (version.isBlank()) {
                version = Json.str(doc, "engine", "");
            }
            if (version.isBlank()) {
                version = Json.str(doc, "version", "");
            }
            project.version = version;
        } catch (Exception ex) {
            project.warnings.add("Could not parse the Cocos project JSON: " + ex.getMessage());
        }
        project.targets = List.of("web-desktop", "web-mobile", "windows", "macos");
        return project;
    }

    private Project loveProject(Path dir) {
        String main = readSmall(dir.resolve("main.lua"));
        Path conf = dir.resolve("conf.lua");
        boolean marker = Files.isRegularFile(conf) || main.contains("love.");
        if (!marker) {
            return null;
        }
        Project project = baseProject("love", dir);
        project.markers.add("main.lua");
        if (Files.isRegularFile(conf)) {
            project.markers.add("conf.lua");
            String identity = match(readSmall(conf),
                    "(?m)^\\s*t\\.identity\\s*=\\s*[\"']([^\"']*)[\"']");
            if (!identity.isBlank()) {
                project.name = identity;
            }
        }
        project.targets = List.of("love", "windows");
        return project;
    }

    private static Project baseProject(String engine, Path dir) {
        Project project = new Project();
        project.engine = engine;
        project.path = dir.toAbsolutePath().normalize();
        project.name = dir.getFileName() == null ? "Game" : dir.getFileName().toString();
        return project;
    }

    private static Path firstByExtension(Path dir, String extension) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir,
                p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(extension))) {
            for (Path item : stream) {
                if (Files.isRegularFile(item)) {
                    return item;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* engine actions                                                     */
    /* ------------------------------------------------------------------ */

    private void build(Project project, EngineInfo engine, Map<String, Object> request,
                       Map<String, Object> out) throws Failure {
        Path output = resolveOutput(request, project);
        int timeout = timeout(request, 900);
        out.put("output", output.toString());
        switch (project.engine) {
            case "godot" -> {
                List<String> command = new ArrayList<>(List.of(
                        engine.executable.toString(), "--headless", "--path",
                        project.path.toString(), "--import"));
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Godot import/build failed", "Godot import finished");
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("artifact_kind", "project");
                out.put("command", exec.command);
                out.put("stdout", tail(exec.out, 20000));
                out.put("stderr", tail(exec.err, 20000));
                out.put("exit_code", exec.exitCode);
                out.put("message", "Godot imported the project headlessly. Use export "
                        + "with a preset to produce a runnable build.");
            }
            case "unity" -> {
                List<String> command = new ArrayList<>(List.of(
                        engine.executable.toString(), "-batchmode", "-quit", "-nographics",
                        "-projectPath", project.path.toString(), "-logFile", "-"));
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Unity script compilation failed", "Unity imported and compiled");
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("artifact_kind", "project");
                out.put("command", exec.command);
                out.put("stdout", tail(exec.out, 20000));
                out.put("stderr", tail(exec.err, 20000));
                out.put("exit_code", exec.exitCode);
                out.put("message", "Unity imported and compiled the project. Use export "
                        + "to build a player.");
            }
            case "unreal" -> {
                List<String> command = unrealBuildCommand(project, engine, "Development");
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Unreal build failed", "Unreal target built");
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("artifact_kind", "project");
                out.put("command", exec.command);
                out.put("stdout", tail(exec.out, 20000));
                out.put("stderr", tail(exec.err, 20000));
                out.put("exit_code", exec.exitCode);
                out.put("message", "Unreal compiled the editor target. Use export to cook "
                        + "and package a build.");
            }
            case "cocos" -> {
                String target = target(request, project, "web-desktop");
                List<String> command = cocosCommand(engine, project, target, output,
                        Json.bool(request, "debug", false));
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Cocos Creator build failed", "Cocos build finished");
                Path artifact = findWebArtifact(output, project.path.resolve("build"));
                if (artifact == null) {
                    throw new Failure("Cocos Creator reported success but no index.html "
                            + "was produced under " + output
                            + ". Check the build platform and project settings.", exec);
                }
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("artifact", artifact.toString());
                out.put("artifact_kind", "web");
                out.put("command", exec.command);
                out.put("stdout", tail(exec.out, 20000));
                out.put("stderr", tail(exec.err, 20000));
                out.put("exit_code", exec.exitCode);
                out.put("message", "Cocos Creator exported the project.");
            }
            default -> throw new Failure("Build is not supported for " + project.engine, null);
        }
    }

    private void export(Project project, EngineInfo engine, Map<String, Object> request,
                        Map<String, Object> out) throws Failure {
        String requestedTarget = Json.str(request, "target", "").trim();
        if (!requestedTarget.isBlank()) {
            target(request, project, project.targets.get(0));
        }
        Path output = resolveOutput(request, project);
        int timeout = timeout(request, 1800);
        out.put("output", output.toString());
        switch (project.engine) {
            case "godot" -> {
                String preset = choosePreset(project, request);
                String platform = presetPlatform(project, preset);
                Path artifact = exportPath(output, project.name, platform);
                List<String> command = new ArrayList<>(List.of(
                        engine.executable.toString(), "--headless", "--path",
                        project.path.toString(), "--export-release", preset,
                        artifact.toString()));
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Godot export failed", "Godot exported");
                if (!Files.exists(artifact)) {
                    throw new Failure("Godot reported success but the export artifact was "
                            + "not created: " + artifact, exec);
                }
                finishArtifact(out, artifact, "godot-export", exec);
                out.put("preset", preset);
            }
            case "unity" -> {
                String target = target(request, project, WINDOWS ? "windows" : MAC ? "macos" : "linux");
                Path artifact = unityOutput(output, project.name, target);
                String flag = switch (target) {
                    case "linux" -> "-buildLinux64Player";
                    case "macos" -> "-buildOSXUniversalPlayer";
                    default -> "-buildWindows64Player";
                };
                List<String> command = new ArrayList<>(List.of(
                        engine.executable.toString(), "-batchmode", "-quit", "-nographics",
                        "-projectPath", project.path.toString(), "-logFile", "-",
                        flag, artifact.toString()));
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Unity player build failed", "Unity player built");
                if (!Files.exists(artifact)) {
                    throw new Failure("Unity reported success but no player was created at "
                            + artifact + ". Check Build Settings scenes in the editor.", exec);
                }
                finishArtifact(out, artifact, "unity-player", exec);
                out.put("target", target);
            }
            case "unreal" -> {
                String target = target(request, project, WINDOWS ? "windows" : MAC ? "macos" : "linux");
                List<String> command = unrealPackageCommand(project, engine, target, output);
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Unreal package failed", "Unreal package finished");
                Path artifact = findFirstArtifact(output, 7);
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("artifact", artifact == null ? output.toString() : artifact.toString());
                out.put("artifact_kind", "unreal-package");
                out.put("command", exec.command);
                out.put("stdout", tail(exec.out, 20000));
                out.put("stderr", tail(exec.err, 20000));
                out.put("exit_code", exec.exitCode);
                out.put("target", target);
                out.put("message", artifact == null
                        ? "Unreal BuildCookRun finished. Archive folder: " + output
                        : "Unreal packaged a build: " + artifact);
                if (artifact == null) {
                    out.put("warning", "No executable was found in the archive folder. "
                            + "Check the packaging target and project maps.");
                }
            }
            case "cocos" -> {
                String target = target(request, project, "web-desktop");
                List<String> command = cocosCommand(engine, project, target, output,
                        Json.bool(request, "debug", false));
                Exec exec = runBlocking(command, project.path, timeout, Map.of());
                ensureOk(exec, "Cocos Creator export failed", "Cocos export finished");
                Path artifact = findWebArtifact(output, project.path.resolve("build"));
                if (artifact == null) {
                    throw new Failure("Cocos Creator reported success but no index.html "
                            + "was produced under " + output
                            + ". Check the build platform and project settings.", exec);
                }
                finishArtifact(out, artifact, "cocos-export", exec);
                out.put("target", target);
            }
            case "love" -> {
                Path lovePackage = packLove(project, output);
                if (!WINDOWS) {
                    finishArtifact(out, lovePackage, "love-package", null);
                    out.put("message", "Created " + lovePackage.getFileName()
                            + ". Fused executables are produced on Windows with love.exe.");
                    return;
                }
                Path exe = output.resolve(safeName(project.name) + ".exe");
                try {
                    Files.createDirectories(output);
                    Files.copy(engine.executable, exe, StandardCopyOption.REPLACE_EXISTING);
                    try (OutputStream outStream = Files.newOutputStream(exe,
                            StandardOpenOption.APPEND);
                         InputStream in = Files.newInputStream(lovePackage)) {
                        in.transferTo(outStream);
                    }
                } catch (IOException ex) {
                    throw new Failure("Could not fuse the LÖVE executable: "
                            + ex.getMessage(), null);
                }
                finishArtifact(out, exe, "love-exe", null);
                out.put("love_package", lovePackage.toString());
                out.put("message", "Created a fused LÖVE executable with love.exe.");
            }
            default -> throw new Failure("Export is not supported for " + project.engine, null);
        }
    }

    private void run(Project project, EngineInfo engine, Map<String, Object> request,
                     Map<String, Object> out) throws Failure {
        Path artifact = resolveArtifact(request, project, out);
        if (artifact != null) {
            Map<String, Object> log = launchFile(artifact, project.path);
            out.put("ok", true);
            out.put("phase", "ready");
            out.put("artifact", artifact.toString());
            out.put("pid", log.get("pid"));
            out.put("log", log.get("log"));
            out.put("message", "Started " + artifact.getFileName());
            return;
        }
        switch (project.engine) {
            case "godot" -> {
                List<String> command = new ArrayList<>(List.of(
                        engine.executable.toString(), "--path", project.path.toString()));
                if (Json.bool(request, "headless", false)) {
                    command = new ArrayList<>(List.of(
                            engine.executable.toString(), "--headless", "--path",
                            project.path.toString(), "--quit-after",
                            String.valueOf(Math.max(1, Json.integer(request, "frames", 120)))));
                    Exec exec = runBlocking(command, project.path, timeout(request, 120), Map.of());
                    ensureOk(exec, "Godot headless run failed", "Godot ran headlessly");
                    out.put("ok", true);
                    out.put("phase", "ready");
                    out.put("command", exec.command);
                    out.put("stdout", tail(exec.out, 20000));
                    out.put("stderr", tail(exec.err, 20000));
                    out.put("exit_code", exec.exitCode);
                    out.put("message", "Godot ran the project headlessly.");
                    return;
                }
                Map<String, Object> log = launch(command, project.path);
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("pid", log.get("pid"));
                out.put("log", log.get("log"));
                out.put("message", "Godot launched the project.");
            }
            case "love" -> {
                List<String> command = List.of(engine.executable.toString(),
                        project.path.toString());
                Map<String, Object> log = launch(command, project.path);
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("pid", log.get("pid"));
                out.put("log", log.get("log"));
                out.put("message", "LÖVE launched the project.");
            }
            case "unity" -> {
                Path player = findFirstArtifact(resolveOutput(request, project), 5);
                if (player != null) {
                    Map<String, Object> log = launchFile(player, project.path);
                    out.put("ok", true);
                    out.put("phase", "ready");
                    out.put("artifact", player.toString());
                    out.put("pid", log.get("pid"));
                    out.put("log", log.get("log"));
                    out.put("message", "Started the built Unity player.");
                    return;
                }
                List<String> command = List.of(engine.executable.toString(),
                        "-projectPath", project.path.toString());
                Map<String, Object> log = launch(command, project.path);
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("pid", log.get("pid"));
                out.put("log", log.get("log"));
                out.put("message", "Opened the Unity Editor. Export a player to run the game.");
            }
            case "unreal" -> {
                Path packaged = findFirstArtifact(resolveOutput(request, project), 7);
                if (packaged != null) {
                    Map<String, Object> log = launchFile(packaged, project.path);
                    out.put("ok", true);
                    out.put("phase", "ready");
                    out.put("artifact", packaged.toString());
                    out.put("pid", log.get("pid"));
                    out.put("log", log.get("log"));
                    out.put("message", "Started the packaged Unreal build.");
                    return;
                }
                Path uproject = firstByExtension(project.path, ".uproject");
                List<String> command = uproject == null
                        ? List.of(engine.executable.toString())
                        : List.of(engine.executable.toString(), uproject.toString());
                Map<String, Object> log = launch(command, project.path);
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("pid", log.get("pid"));
                out.put("log", log.get("log"));
                out.put("message", "Opened the Unreal Editor. Export a package to run the game.");
            }
            case "cocos" -> {
                List<String> command = List.of(engine.executable.toString(),
                        "--project", project.path.toString());
                Map<String, Object> log = launch(command, project.path);
                out.put("ok", true);
                out.put("phase", "ready");
                out.put("pid", log.get("pid"));
                out.put("log", log.get("log"));
                out.put("message", "Opened Cocos Creator. Use its preview to run the game.");
            }
            default -> throw new Failure("Run is not supported for " + project.engine, null);
        }
    }

    private Path resolveArtifact(Map<String, Object> request, Project project,
                                 Map<String, Object> out) {
        String raw = firstNonBlank(Json.str(request, "artifact", ""),
                Json.str(request, "exe", ""));
        if (raw.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(raw);
            if (!path.isAbsolute()) {
                path = project.path.resolve(path);
            }
            path = path.normalize().toAbsolutePath();
            if (!Files.isRegularFile(path)) {
                return null;
            }
            Path output = resolveOutput(request, project).toAbsolutePath().normalize();
            boolean insideProject = path.startsWith(project.path.toAbsolutePath().normalize());
            boolean insideOutput = path.startsWith(output);
            return insideProject || insideOutput ? path : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* commands                                                           */
    /* ------------------------------------------------------------------ */

    private static List<String> unrealBuildCommand(Project project, EngineInfo engine,
                                                   String configuration) throws Failure {
        Path batch = engine.engineRoot.resolve(WINDOWS
                ? "Build/BatchFiles/Build.bat" : "Build/BatchFiles/Build.sh");
        if (!Files.isRegularFile(batch)) {
            throw new Failure("Unreal Build script not found at " + batch
                    + ". Check that UE_ROOT points to the engine folder containing Engine/.", null);
        }
        Path uproject = firstByExtension(project.path, ".uproject");
        if (uproject == null) {
            throw new Failure("No .uproject file found in " + project.path, null);
        }
        String platform = WINDOWS ? "Win64" : MAC ? "Mac" : "Linux";
        return new ArrayList<>(List.of(
                batch.toString(), project.name + "Editor", platform, configuration,
                "-Project=" + uproject, "-WaitMutex", "-FromMsBuild"));
    }

    private static List<String> unrealPackageCommand(Project project, EngineInfo engine,
                                                     String target, Path output)
            throws Failure {
        Path uat = engine.engineRoot.resolve(WINDOWS
                ? "Build/BatchFiles/RunUAT.bat" : "Build/BatchFiles/RunUAT.sh");
        if (!Files.isRegularFile(uat)) {
            throw new Failure("Unreal RunUAT script not found at " + uat, null);
        }
        Path uproject = firstByExtension(project.path, ".uproject");
        if (uproject == null) {
            throw new Failure("No .uproject file found in " + project.path, null);
        }
        String platform = switch (target) {
            case "linux" -> "Linux";
            case "macos" -> "Mac";
            default -> "Win64";
        };
        try {
            Files.createDirectories(output);
        } catch (IOException ex) {
            throw new Failure("Could not create the Unreal archive folder: " + output
                    + " (" + ex.getMessage() + ")", null);
        }
        return new ArrayList<>(List.of(
                uat.toString(), "BuildCookRun", "-project=" + uproject,
                "-noP4", "-platform=" + platform, "-clientconfig=Development",
                "-cook", "-build", "-stage", "-pak", "-archive",
                "-archivedirectory=" + output, "-utf8output"));
    }

    private static List<String> cocosCommand(EngineInfo engine, Project project,
                                             String target, Path output, boolean debug) {
        String platform = switch (target) {
            case "web-mobile" -> "web-mobile";
            case "windows" -> "win32";
            case "macos" -> "mac";
            default -> "web-desktop";
        };
        String options = "platform=" + platform + ";debug=" + debug
                + ";buildPath=" + output.toString();
        return new ArrayList<>(List.of(engine.executable.toString(),
                "--project", project.path.toString(), "--build", options));
    }

    private static void ensureOk(Exec exec, String failureMessage, String successMessage)
            throws Failure {
        if (!exec.launched || exec.timedOut || exec.exitCode != 0) {
            String detail = exec.timedOut ? " (timed out after " + exec.timeoutSeconds + "s)"
                    : exec.error.isBlank() ? "" : " (" + exec.error + ")";
            throw new Failure(failureMessage + detail, exec);
        }
    }

    private void finishArtifact(Map<String, Object> out, Path artifact, String kind,
                                Exec exec) {
        out.put("ok", true);
        out.put("phase", "ready");
        out.put("artifact", artifact.toString());
        out.put("artifact_kind", kind);
        if (exec != null) {
            out.put("command", exec.command);
            out.put("stdout", tail(exec.out, 20000));
            out.put("stderr", tail(exec.err, 20000));
            out.put("exit_code", exec.exitCode);
        }
        out.put("message", "Created " + artifact.getFileName());
    }

    private static String choosePreset(Project project, Map<String, Object> request)
            throws Failure {
        String wanted = Json.str(request, "preset", "").trim();
        if (!wanted.isBlank()) {
            for (Map<String, Object> preset : project.presets) {
                if (wanted.equals(Json.str(preset, "name", ""))
                        || wanted.equals(Json.str(preset, "id", ""))) {
                    return Json.str(preset, "name", wanted);
                }
            }
            throw new Failure("Godot export preset not found: " + wanted
                    + ". Available: " + presetNames(project), null);
        }
        if (project.presets.size() == 1) {
            return Json.str(project.presets.get(0), "name", "");
        }
        if (project.presets.isEmpty()) {
            throw new Failure("No Godot export preset exists. Open the project in Godot, "
                    + "Editor > Manage Export Templates, and add an export preset.", null);
        }
        throw new Failure("Choose one Godot export preset: " + presetNames(project), null);
    }

    private static String presetNames(Project project) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> preset : project.presets) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(Json.str(preset, "name", ""));
        }
        return out.toString();
    }

    private static String presetPlatform(Project project, String presetName) {
        for (Map<String, Object> preset : project.presets) {
            if (presetName.equals(Json.str(preset, "name", ""))) {
                String platform = Json.str(preset, "platform", "").toLowerCase(Locale.ROOT);
                if (platform.contains("linux")) {
                    return "linux";
                }
                if (platform.contains("mac") || platform.contains("osx")) {
                    return "macos";
                }
                if (platform.contains("web") || platform.contains("html")) {
                    return "web";
                }
                return "windows";
            }
        }
        return WINDOWS ? "windows" : MAC ? "macos" : "linux";
    }

    private static Path exportPath(Path output, String name, String target) {
        String extension = switch (target) {
            case "linux" -> ".x86_64";
            case "macos" -> ".zip";
            case "web" -> ".html";
            default -> ".exe";
        };
        return output.resolve(safeName(name) + extension);
    }

    private static Path unityOutput(Path output, String name, String target) {
        return switch (target) {
            case "linux" -> output.resolve(safeName(name) + ".x86_64");
            case "macos" -> output.resolve(safeName(name) + ".app");
            default -> output.resolve(safeName(name) + ".exe");
        };
    }

    private static Path findWebArtifact(Path preferred, Path fallback) {
        Path index = preferred.resolve("index.html");
        if (Files.isRegularFile(index)) {
            return index;
        }
        if (fallback != null && Files.isDirectory(fallback)) {
            try (Stream<Path> walk = Files.walk(fallback, 3)) {
                Path found = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase("index.html"))
                        .findFirst().orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static Path findFirstArtifact(Path base, int depth) {
        if (base == null || !Files.isDirectory(base)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(base, depth)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lower.endsWith(".exe") || lower.endsWith(".x86_64")
                                || lower.endsWith(".app");
                    })
                    .filter(path -> !path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .contains("crashhandler"))
                    .findFirst().orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* packaging                                                          */
    /* ------------------------------------------------------------------ */

    private Path packLove(Project project, Path output) throws Failure {
        Path target = output.resolve(safeName(project.name) + ".love")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(output);
            long[] total = {0};
            int[] count = {0};
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(project.path)) {
                    files = walk.filter(Files::isRegularFile)
                            .filter(path -> !insideSkipped(project.path.relativize(path)))
                            .filter(path -> !path.toAbsolutePath().normalize().equals(target))
                            .filter(path -> !path.startsWith(output.toAbsolutePath().normalize()))
                            .sorted()
                            .toList();
                }
                for (Path file : files) {
                    if (Files.isSymbolicLink(file)) {
                        continue;
                    }
                    long size = Files.size(file);
                    if (++count[0] > MAX_LOVE_FILES || total[0] + size > MAX_LOVE_BYTES) {
                        throw new Failure("Project is too large to package as .love "
                                + "(limit " + MAX_LOVE_FILES + " files / "
                                + (MAX_LOVE_BYTES / (1024 * 1024)) + " MB).", null);
                    }
                    total[0] += size;
                    String name = project.path.relativize(file).toString()
                            .replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            return target;
        } catch (Failure ex) {
            throw ex;
        } catch (IOException ex) {
            throw new Failure("Could not package the LÖVE project: " + ex.getMessage(), null);
        }
    }

    private static boolean insideSkipped(Path relative) {
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* paths                                                              */
    /* ------------------------------------------------------------------ */

    private Path resolveExisting(String raw) throws Failure {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\")) {
            value = home.resolve(value.substring(value.length() > 1 ? 2 : 1)).toString();
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (Exception ex) {
            throw new Failure("Invalid project path: " + raw, null);
        }
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        path = path.normalize().toAbsolutePath();
        if (!Files.exists(path)) {
            throw new Failure("Project path not found: " + path, null);
        }
        return Files.isDirectory(path) ? path : path.getParent();
    }

    private Path resolveOutput(Map<String, Object> request, Project project) throws Failure {
        String raw = firstNonBlank(Json.str(request, "output", ""),
                Json.str(request, "destination", ""));
        Path base;
        if (raw.isBlank() || raw.equalsIgnoreCase("desktop") || raw.equals("桌面")) {
            base = FileService.desktopDir().resolve("CodeForge Engine Exports")
                    .resolve(safeName(project.name));
        } else {
            try {
                base = Path.of(raw.trim());
            } catch (Exception ex) {
                throw new Failure("Invalid output path: " + raw, null);
            }
            if (!base.isAbsolute()) {
                base = FileService.desktopDir().resolve(base);
            }
            base = base.normalize().toAbsolutePath();
        }
        try {
            Files.createDirectories(base);
        } catch (IOException ex) {
            throw new Failure("Could not create the output folder: " + base
                    + " (" + ex.getMessage() + ")", null);
        }
        return base;
    }

    private static int timeout(Map<String, Object> request, int fallback) {
        int value = Json.integer(request, "timeout", fallback);
        return Math.max(10, Math.min(7200, value));
    }

    private static String target(Map<String, Object> request, Project project,
                                 String fallback) throws Failure {
        String value = Json.str(request, "target", "").trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return fallback;
        }
        if (project.targets.contains(value)) {
            return value;
        }
        throw new Failure("\"" + value + "\" is not a supported export target for "
                + project.engine + ". Supported: "
                + String.join(", ", project.targets), null);
    }

    /* ------------------------------------------------------------------ */
    /* processes                                                          */
    /* ------------------------------------------------------------------ */

    private static final class Exec {
        boolean launched;
        boolean timedOut;
        int exitCode = -1;
        int timeoutSeconds;
        String out = "";
        String err = "";
        String command = "";
        String error = "";
        long durationMs;
    }

    private Exec runBlocking(List<String> command, Path cwd, int timeoutSeconds,
                             Map<String, String> extraEnv) {
        Exec result = new Exec();
        result.timeoutSeconds = timeoutSeconds;
        result.command = displayCommand(command);
        List<String> prepared = prepare(command);
        ProcessBuilder builder = new ProcessBuilder(prepared).directory(cwd.toFile());
        Map<String, String> merged = new LinkedHashMap<>(env);
        if (extraEnv != null) {
            merged.putAll(extraEnv);
        }
        if (WINDOWS) {
            String path = merged.getOrDefault("PATH", merged.getOrDefault("Path", ""));
            merged.put("PATH", path);
            merged.put("Path", path);
        }
        builder.environment().putAll(merged);
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            result.error = ex.getMessage();
            return result;
        }
        result.launched = true;
        long started = System.currentTimeMillis();
        Capture stdout = new Capture(process.getInputStream());
        Capture stderr = new Capture(process.getErrorStream());
        stdout.start();
        stderr.start();
        boolean done;
        try {
            done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            done = false;
        }
        if (!done) {
            result.timedOut = true;
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            stdout.join(3000);
            stderr.join(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        result.out = stdout.text();
        result.err = stderr.text();
        result.exitCode = process.isAlive() ? 124 : process.exitValue();
        result.durationMs = System.currentTimeMillis() - started;
        return result;
    }

    private Map<String, Object> launch(List<String> command, Path cwd) throws Failure {
        Path logs = workspace.resolve("logs");
        try {
            Files.createDirectories(logs);
        } catch (IOException ex) {
            throw new Failure("Could not create the engine log folder: " + logs, null);
        }
        Path log = logs.resolve("engine-" + LocalDateTime.now().format(STAMP)
                + "-" + ProcessHandle.current().pid() + ".log");
        ProcessBuilder builder = new ProcessBuilder(prepare(command))
                .directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().putAll(env);
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new Failure("Could not launch " + displayCommand(command)
                    + ": " + ex.getMessage(), null);
        }
        return Json.map("pid", process.pid(), "log", log.toString());
    }

    private Map<String, Object> launchFile(Path artifact, Path cwd) throws Failure {
        List<String> command = new ArrayList<>();
        if (MAC && artifact.toString().endsWith(".app")) {
            command.add("open");
            command.add(artifact.toString());
        } else if (!WINDOWS && !Files.isExecutable(artifact)
                && artifact.toString().endsWith(".x86_64")) {
            command.add("chmod");
            command.add("+x");
            command.add(artifact.toString());
            try {
                new ProcessBuilder(command).start().waitFor();
            } catch (Exception ignored) {
            }
            command = List.of(artifact.toString());
        } else {
            command.add(artifact.toString());
        }
        return launch(command, cwd);
    }

    private static List<String> prepare(List<String> command) {
        if (!WINDOWS || command.isEmpty()) {
            return command;
        }
        String first = command.get(0).toLowerCase(Locale.ROOT);
        if (!first.endsWith(".bat") && !first.endsWith(".cmd")) {
            return command;
        }
        List<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/d", "/s", "/c"));
        StringBuilder line = new StringBuilder();
        for (String arg : command) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(cmdArg(arg));
        }
        wrapped.add(line.toString());
        return wrapped;
    }

    private static String cmdArg(String value) {
        if (value.isEmpty()) {
            return "\"\"";
        }
        if (!value.matches(".*[\\s&|<>^].*") && !value.contains("\"")) {
            return value.replace("%", "%%");
        }
        return "\"" + value.replace("\"", "").replace("%", "%%") + "\"";
    }

    private static String displayCommand(List<String> command) {
        StringBuilder out = new StringBuilder();
        for (String arg : command) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(arg.contains(" ") ? "\"" + arg + "\"" : arg);
        }
        return out.toString();
    }

    private static String probeVersion(Path exe, List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(prepare(command));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[4096];
                try (InputStream input = process.getInputStream()) {
                    int read;
                    int total = 0;
                    while ((read = input.read(chunk)) >= 0) {
                        if (total < 8192) {
                            buffer.write(chunk, 0, Math.min(read, 8192 - total));
                        }
                        total += read;
                    }
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return "";
            }
            reader.join(1000);
            String text = Encodings.decode(buffer.toByteArray()).trim();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    return line.length() > 120 ? line.substring(0, 120) : line;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static final class Capture extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private String text = "";

        Capture(InputStream input) {
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    synchronized (kept) {
                        int room = 256 * 1024 - kept.size();
                        if (room > 0) {
                            kept.write(buffer, 0, Math.min(room, read));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            synchronized (kept) {
                text = Encodings.decode(kept.toByteArray());
            }
        }

        String text() {
            synchronized (kept) {
                return text;
            }
        }
    }

    private static final class Failure extends Exception {
        final Exec exec;

        Failure(String message, Exec exec) {
            super(message);
            this.exec = exec;
        }
    }

    private static void putExec(Map<String, Object> out, Exec exec) {
        out.put("command", exec.command);
        out.put("exit_code", exec.exitCode);
        out.put("timed_out", exec.timedOut);
        out.put("stdout", tail(exec.out, 20000));
        out.put("stderr", tail(exec.err, 20000));
        if (!exec.error.isBlank()) {
            out.put("launch_error", exec.error);
        }
    }

    /* ------------------------------------------------------------------ */
    /* text helpers                                                       */
    /* ------------------------------------------------------------------ */

    private static String readSmall(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            long size = Files.size(path);
            if (size > MAX_READ) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String match(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "").trim();
        while (value.endsWith(".") || value.endsWith(" ")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank()) {
            value = "Game";
        }
        if (value.length() > 60) {
            value = value.substring(0, 60).trim();
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String tail(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(text.length() - limit);
    }
}