package codeforge.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Provisions missing command-line tools on demand. Portable toolchains are
 * preferred because they need no administrator rights; otherwise the native
 * package manager (winget, apt, dnf, pacman, zypper, apk, brew) is used.
 */
public final class ToolProvisioner {
    public interface Listener {
        void progress(String tool, String state, int percent, String message);
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final String EXE = WINDOWS ? ".exe" : "";
    private static final String PATH_KEY = WINDOWS ? "Path" : "PATH";

    private static final class ToolPackage {
        final String id;
        final String name;
        final List<String> winget;
        final List<String> apt;
        final List<String> dnf;
        final List<String> pacman;
        final List<String> zypper;
        final List<String> apk;
        final List<String> brew;

        ToolPackage(String id, String name, List<String> winget, List<String> apt,
                    List<String> dnf, List<String> pacman, List<String> zypper,
                    List<String> apk, List<String> brew) {
            this.id = id;
            this.name = name;
            this.winget = winget;
            this.apt = apt;
            this.dnf = dnf;
            this.pacman = pacman;
            this.zypper = zypper;
            this.apk = apk;
            this.brew = brew;
        }
    }

    private static final Map<String, String> MANAGED = new LinkedHashMap<>();
    private static final Map<String, ToolPackage> PACKAGES = new LinkedHashMap<>();
    private static final Map<String, String> PIP = new LinkedHashMap<>();
    private static final Map<String, String> NPM = new LinkedHashMap<>();
    private static final Set<String> WINGET_IGNORE_DEPENDENCIES = Set.of("yt-dlp");
    private static final Set<String> PIP_FIRST = Set.of("yt-dlp");

    static {
        MANAGED.put("python", "python");
        MANAGED.put("pip", "python");
        MANAGED.put("node", "node");
        MANAGED.put("go", "go");
        MANAGED.put("rust", "rust");
        MANAGED.put("php", "php");
        MANAGED.put("dotnet", "dotnet");

        register("python", "Python 3", "Python.Python.3.12",
                "python3 python3-pip", "python3 python3-pip", "python python-pip",
                "python3 python3-pip", "python3 py3-pip", "python");
        register("node", "Node.js", "OpenJS.NodeJS.LTS",
                "nodejs npm", "nodejs npm", "nodejs npm", "nodejs npm",
                "nodejs npm", "node");
        register("go", "Go", "GoLang.Go",
                "golang-go", "golang", "go", "go", "go", "go");
        register("rust", "Rust", "Rustlang.Rustup",
                "rustc cargo", "rustc cargo", "rust", "rust cargo",
                "rust cargo", "rustup");
        register("php", "PHP", "PHP.PHP.8.3",
                "php-cli", "php-cli", "php", "php8-cli", "php83", "php");
        register("dotnet", ".NET SDK", "Microsoft.DotNet.SDK.10",
                "dotnet-sdk-10.0", "dotnet-sdk-10.0", "dotnet-sdk",
                "dotnet-sdk-10.0", "dotnet10-sdk", "dotnet");
        register("java", "Java JDK", "EclipseAdoptium.Temurin.21.JDK",
                "default-jdk", "java-21-openjdk-devel", "jdk-openjdk",
                "java-21-openjdk-devel", "openjdk21", "openjdk");
        register("gcc", "GCC / G++", "BrechtSanders.WinLibs.POSIX.UCRT",
                "build-essential", "gcc gcc-c++ make", "base-devel",
                "gcc gcc-c++ make", "build-base", "gcc");
        register("clang", "Clang / LLVM", "LLVM.LLVM",
                "clang", "clang", "clang", "clang", "clang", "llvm");
        register("git", "Git", "Git.Git",
                "git", "git", "git", "git", "git", "git");
        register("cmake", "CMake", "Kitware.CMake",
                "cmake", "cmake", "cmake", "cmake", "cmake", "cmake");
        register("ninja", "Ninja", "Ninja-build.Ninja",
                "ninja-build", "ninja-build", "ninja", "ninja", "ninja", "ninja");
        register("make", "Make", "BrechtSanders.WinLibs.POSIX.UCRT",
                "make", "make", "base-devel", "make", "build-base", "make");
        register("ffmpeg", "FFmpeg", "Gyan.FFmpeg",
                "ffmpeg", "ffmpeg", "ffmpeg", "ffmpeg", "ffmpeg", "ffmpeg");
        register("7z", "7-Zip", "7zip.7zip",
                "p7zip-full", "p7zip", "p7zip", "p7zip", "p7zip", "sevenzip");
        register("imagemagick", "ImageMagick", "ImageMagick.ImageMagick",
                "imagemagick", "ImageMagick", "imagemagick", "ImageMagick",
                "imagemagick", "imagemagick");
        register("sqlite3", "SQLite", "SQLite.SQLite",
                "sqlite3", "sqlite", "sqlite", "sqlite3", "sqlite", "sqlite");
        register("psql", "PostgreSQL client", "PostgreSQL.PostgreSQL.17",
                "postgresql-client", "postgresql", "postgresql",
                "postgresql-client", "postgresql-client", "postgresql");
        register("mysql", "MySQL client", "Oracle.MySQL",
                "default-mysql-client", "mysql", "mariadb-clients",
                "mariadb-client", "mariadb-client", "mysql-client");
        register("ruby", "Ruby", "RubyInstallerTeam.Ruby.3.3",
                "ruby", "ruby", "ruby", "ruby", "ruby", "ruby");
        register("perl", "Perl", "StrawberryPerl.StrawberryPerl",
                "perl", "perl", "perl", "perl", "perl", "perl");
        register("kotlin", "Kotlin", "JetBrains.Kotlin",
                "kotlin", "kotlin", "kotlin", "kotlin", "kotlin", "kotlin");
        register("gradle", "Gradle", "Gradle.Gradle",
                "gradle", "gradle", "gradle", "gradle", "gradle", "gradle");
        register("maven", "Maven", "Apache.Maven",
                "maven", "maven", "maven", "maven", "maven", "maven");
        register("docker", "Docker", "Docker.DockerDesktop",
                "docker.io", "docker", "docker", "docker", "docker", "docker");
        register("podman", "Podman", "RedHat.Podman",
                "podman", "podman", "podman", "podman", "podman", "podman");
        register("curl", "curl", "cURL.cURL",
                "curl", "curl", "curl", "curl", "curl", "curl");
        register("wget", "Wget", "GnuWin32.Wget",
                "wget", "wget", "wget", "wget", "wget", "wget");
        register("rsync", "rsync", "",
                "rsync", "rsync", "rsync", "rsync", "rsync", "rsync");

        register("mariadb", "MariaDB client", "MariaDB.Server",
                "mariadb-client", "mariadb", "mariadb-clients",
                "mariadb-client", "mariadb-client", "mariadb");
        register("redis", "Redis CLI", "Redis.Redis",
                "redis-tools", "redis", "redis", "redis", "redis", "redis");
        register("mongosh", "MongoDB Shell", "MongoDB.Shell",
                "", "", "", "", "", "mongosh");
        register("mongodump", "MongoDB Database Tools", "MongoDB.DatabaseTools",
                "mongodb-database-tools", "", "", "", "", "mongodb-database-tools");
        register("sqlcmd", "SQL Server sqlcmd", "Microsoft.Sqlcmd",
                "", "", "", "", "", "sqlcmd");
        register("clickhouse", "ClickHouse client", "",
                "clickhouse-client", "clickhouse-client", "clickhouse",
                "", "", "clickhouse");
        register("influx", "InfluxDB CLI", "InfluxData.InfluxDB.CLI",
                "", "", "", "", "", "influxdb-cli");
        register("duckdb", "DuckDB CLI", "",
                "duckdb", "", "duckdb", "", "", "duckdb");
        register("cypher-shell", "Neo4j cypher-shell", "",
                "", "", "", "", "", "neo4j");
        register("mysqlsh", "MySQL Shell", "Oracle.MySQLShell",
                "", "", "", "", "", "mysql-shell");

        register("gh", "GitHub CLI", "GitHub.cli",
                "gh", "gh", "github-cli", "gh", "github-cli", "gh");
        register("aws", "AWS CLI", "Amazon.AWSCLI",
                "awscli", "awscli", "aws-cli", "aws-cli", "aws-cli", "awscli");
        register("az", "Azure CLI", "Microsoft.AzureCLI",
                "azure-cli", "azure-cli", "azure-cli", "azure-cli",
                "azure-cli", "azure-cli");
        register("gcloud", "Google Cloud SDK", "Google.CloudSDK",
                "google-cloud-cli", "google-cloud-cli", "google-cloud-cli",
                "google-cloud-cli", "google-cloud-cli", "google-cloud-sdk");
        register("kubectl", "kubectl", "Kubernetes.kubectl",
                "kubectl", "kubernetes-client", "kubectl", "kubernetes-client",
                "kubectl", "kubernetes-cli");
        register("helm", "Helm", "Helm.Helm",
                "helm", "helm", "helm", "helm", "helm", "helm");
        register("rclone", "rclone", "Rclone.Rclone",
                "rclone", "rclone", "rclone", "rclone", "rclone", "rclone");
        register("pandoc", "Pandoc", "JohnMacFarlane.Pandoc",
                "pandoc", "pandoc", "pandoc", "pandoc", "pandoc", "pandoc");
        register("tesseract", "Tesseract OCR", "UB-Mannheim.TesseractOCR",
                "tesseract-ocr", "tesseract", "tesseract", "tesseract-ocr",
                "tesseract-ocr", "tesseract");
        register("yt-dlp", "yt-dlp", "yt-dlp.yt-dlp",
                "yt-dlp", "yt-dlp", "yt-dlp", "yt-dlp", "yt-dlp", "yt-dlp");
        register("qpdf", "QPDF", "QPDF.QPDF",
                "qpdf", "qpdf", "qpdf", "qpdf", "qpdf", "qpdf");
        register("gs", "Ghostscript", "ArtifexSoftware.GhostScript",
                "ghostscript", "ghostscript", "ghostscript", "ghostscript",
                "ghostscript", "ghostscript");
        register("exiftool", "ExifTool", "OliverBetz.ExifTool",
                "libimage-exiftool-perl", "perl-Image-ExifTool",
                "perl-image-exiftool", "exiftool", "exiftool", "exiftool");
        register("vlc", "VLC media player", "VideoLAN.VLC",
                "vlc", "vlc", "vlc", "vlc", "vlc", "vlc");
        register("firefox", "Mozilla Firefox", "Mozilla.Firefox",
                "firefox", "firefox", "firefox", "firefox", "firefox", "firefox");
        register("google-chrome", "Google Chrome", "Google.Chrome",
                "google-chrome-stable", "google-chrome-stable", "google-chrome",
                "google-chrome-stable", "chromium", "google-chrome");
        register("libreoffice", "LibreOffice", "TheDocumentFoundation.LibreOffice",
                "libreoffice", "libreoffice", "libreoffice-fresh",
                "libreoffice", "libreoffice", "libreoffice");
        register("gimp", "GIMP", "GIMP.GIMP",
                "gimp", "gimp", "gimp", "gimp", "gimp", "gimp");
        register("inkscape", "Inkscape", "Inkscape.Inkscape",
                "inkscape", "inkscape", "inkscape", "inkscape", "inkscape", "inkscape");
        register("obs", "OBS Studio", "OBSProject.OBSStudio",
                "obs-studio", "obs-studio", "obs-studio", "obs-studio",
                "obs-studio", "obs-studio");
        register("code", "Visual Studio Code", "Microsoft.VisualStudioCode",
                "code", "code", "code", "code", "code", "visual-studio-code");
        register("ollama", "Ollama", "Ollama.Ollama",
                "", "", "ollama", "ollama", "", "ollama");

        PIP.put("playwright", "playwright");
        PIP.put("selenium", "selenium");
        PIP.put("yt-dlp", "yt-dlp");
        NPM.put("playwright", "playwright");
        NPM.put("puppeteer", "puppeteer");

        alias("pg_dump", "psql");
        alias("pg_restore", "psql");
        alias("pg_isready", "psql");
        alias("pg_basebackup", "psql");
        alias("createdb", "psql");
        alias("dropdb", "psql");
        alias("createuser", "psql");
        alias("vacuumdb", "psql");
        alias("mysqldump", "mysql");
        alias("mysqladmin", "mysql");
        alias("mariadb-dump", "mariadb");
        alias("redis-cli", "redis");
        alias("redis-server", "redis");
        alias("redis-benchmark", "redis");
        alias("mongorestore", "mongodump");
        alias("mongoimport", "mongodump");
        alias("mongoexport", "mongodump");
        alias("soffice", "libreoffice");
        alias("gswin64c", "gs");
        alias("gswin32c", "gs");
        alias("google-chrome-stable", "google-chrome");
        alias("chrome", "google-chrome");
        alias("chromium", "google-chrome");
        alias("clickhouse-client", "clickhouse");
        alias("clickhouse-local", "clickhouse");
        alias("bcp", "sqlcmd");

        PIP.put("cqlsh", "cqlsh");
        PIP.put("pgcli", "pgcli");
        PIP.put("mycli", "mycli");
        PIP.put("litecli", "litecli");
        PIP.put("sqlite-utils", "sqlite-utils");
        NPM.put("mongosh", "mongosh");
    }

    private static void register(String id, String name, String winget,
                                 String apt, String dnf, String pacman,
                                 String zypper, String apk, String brew) {
        PACKAGES.put(id, new ToolPackage(id, name, words(winget), words(apt),
                words(dnf), words(pacman), words(zypper), words(apk), words(brew)));
    }

    private static List<String> words(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.trim().split("\\s+"));
    }

    /** A command shipped inside another package, e.g. pg_dump in PostgreSQL. */
    private static void alias(String command, String packageId) {
        ToolPackage source = PACKAGES.get(packageId);
        if (source == null) {
            return;
        }
        PACKAGES.put(command, new ToolPackage(command, source.name, source.winget,
                source.apt, source.dnf, source.pacman, source.zypper,
                source.apk, source.brew));
    }

    private final Path root;
    private final ToolchainManager toolchains;
    private final Set<String> extraDirs = java.util.Collections.synchronizedSet(
            new LinkedHashSet<>());
    private final Object pathLock = new Object();
    private volatile List<String> cachedPath = List.of();
    private volatile long cachedPathAt;

    public ToolProvisioner(Path root, ToolchainManager toolchains) {
        this.root = root;
        this.toolchains = toolchains;
    }

    public boolean canProvide(String command) {
        String tool = canonical(command);
        return MANAGED.containsKey(tool) || PACKAGES.containsKey(tool)
                || PIP.containsKey(tool) || NPM.containsKey(tool);
    }

    public Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        List<String> prepend = new ArrayList<>();
        for (String id : new String[]{"python", "node", "go", "dotnet", "rust", "php", "java", "gcc"}) {
            Map<String, String> extra = toolchains.environment(id);
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                if ("PATH".equalsIgnoreCase(entry.getKey())) {
                    for (String part : splitPath(entry.getValue())) {
                        addUnique(prepend, part, WINDOWS);
                    }
                } else {
                    env.put(entry.getKey(), entry.getValue());
                }
            }
        }
        List<String> merged = new ArrayList<>(prepend);
        for (String part : systemPathPieces()) {
            addUnique(merged, part, WINDOWS);
        }
        String joined = String.join(File.pathSeparator, merged);
        env.put("PATH", joined);
        if (WINDOWS) {
            env.put("Path", joined);
        }
        env.putIfAbsent("LANG", "C.UTF-8");
        env.putIfAbsent("LC_ALL", "C.UTF-8");
        return env;
    }

    public void refreshPath() {
        synchronized (pathLock) {
            cachedPath = List.of();
            cachedPathAt = 0L;
        }
        toolchains.refreshDetection();
    }

    private String findRequested(String requested) {
        for (String name : requestedNames(requested)) {
            String path = findCommand(name);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    /** Only names that are the same executable or drop-in aliases. */
    private static List<String> requestedNames(String command) {
        String base = stripExe(command == null ? "" : command);
        String value = base.toLowerCase(Locale.ROOT);
        List<String> names = switch (value) {
            case "python", "python3", "py" -> List.of("python", "python3", "py");
            case "pip", "pip3" -> List.of("pip", "pip3");
            case "node", "nodejs" -> List.of("node", "nodejs");
            case "7z", "7za", "7zr" -> List.of("7z", "7za", "7zr");
            case "make", "gmake", "mingw32-make" ->
                    List.of("make", "gmake", "mingw32-make");
            case "sqlite", "sqlite3" -> List.of("sqlite3", "sqlite");
            case "gs", "gswin64c", "gswin32c" ->
                    List.of("gs", "gswin64c", "gswin32c");
            case "libreoffice", "soffice" -> List.of("libreoffice", "soffice");
            case "chrome", "google-chrome", "google-chrome-stable" ->
                    List.of("google-chrome", "google-chrome-stable", "chrome", "chromium");
            case "code", "code-insiders" -> List.of("code", "code-insiders");
            default -> base.isEmpty() ? List.of() : List.of(base);
        };
        return names;
    }

    public String findCommand(String command) {
        String base = baseName(command);
        if (base.isEmpty()) {
            return null;
        }
        List<String> candidates = requestedNames(base);
        for (String name : candidates) {
            String path = findOnPath(name);
            if (path != null) {
                return path;
            }
        }
        if (WINDOWS) {
            for (String name : candidates) {
                String path = whereExe(name);
                if (path != null && !path.toLowerCase(Locale.ROOT).contains("windowsapps")) {
                    return path;
                }
                if (path != null && "winget".equals(name)) {
                    return path;
                }
            }
        }
        return null;
    }

    private String whereExe(String name) {
        Process process = null;
        try {
            process = new ProcessBuilder("where.exe", name)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                killTree(process);
                return null;
            }
            String text = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return null;
            }
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null && process.isAlive()) {
                killTree(process);
            }
        }
        return null;
    }

    public List<Object> provisionable() {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(PACKAGES.keySet());
        ids.addAll(MANAGED.keySet());
        ids.addAll(PIP.keySet());
        ids.addAll(NPM.keySet());
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (String id : ids) {
            String path = findCommand(id);
            String name = PACKAGES.containsKey(id) ? PACKAGES.get(id).name : id;
            Map<String, Object> existing = byName.get(name);
            if (existing == null) {
                byName.put(name, Json.map(
                        "id", id,
                        "name", name,
                        "available", path != null,
                        "path", path == null ? "" : path,
                        "method", MANAGED.containsKey(id) ? "portable+package" : "package"));
            } else if (path != null && !Boolean.TRUE.equals(existing.get("available"))) {
                existing.put("id", id);
                existing.put("available", true);
                existing.put("path", path);
                existing.put("method", MANAGED.containsKey(id) ? "portable+package" : "package");
            }
        }
        List<Object> out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparing(item -> String.valueOf(((Map<?, ?>) item).get("name")),
                String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public Map<String, Object> ensureCommand(String requested, int timeoutSeconds,
                                             Listener listener) {
        long started = System.currentTimeMillis();
        String base = baseName(requested);
        String tool = canonical(base);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("available", false);
        out.put("installed", false);
        out.put("requested", requested == null ? "" : requested);
        out.put("tool", tool);
        if (base.isEmpty() || tool.isEmpty() || !canProvide(tool)) {
            out.put("error", "No automatic installer is mapped for '"
                    + (base.isEmpty() ? requested : base) + "'");
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }
        String existing = findRequested(base);
        if (existing != null) {
            out.put("ok", true);
            out.put("available", true);
            out.put("path", existing);
            out.put("method", "already-present");
            out.put("message", tool + " is already available");
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }

        List<String> errors = new ArrayList<>();
        String managedId = MANAGED.get(tool);
        if (managedId != null) {
            notify(listener, tool, "resolving", -1,
                    "preparing portable " + managedId + " toolchain");
            Map<String, Object> managed = toolchains.ensureBlocking(
                    managedId, true, timeoutSeconds,
                    progress -> notify(listener, tool,
                            Json.str(progress, "state", "installing"),
                            Json.integer(progress, "percent", -1),
                            Json.str(progress, "message", "installing " + managedId)));
            String state = Json.str(managed, "state", "");
            if ("ready".equals(state)) {
                refreshPath();
                String path = findRequested(base);
                if (path != null) {
                    Map<String, Object> done = success(out, tool, path, "portable", managedId,
                            "installed portable " + managedId,
                            Json.str(managed, "version", ""), started);
                    notify(listener, tool, "ready", 100,
                            "installed " + managedId + " · " + path);
                    return done;
                }
                errors.add("portable " + managedId
                        + " is ready but the command was not found on PATH");
            } else {
                String error = Json.str(managed, "error", "");
                errors.add("portable " + managedId + " " + state
                        + (error.isEmpty() ? "" : ": " + error));
            }
        }

        boolean pipTried = false;
        if (PIP.containsKey(tool) && PIP_FIRST.contains(tool)) {
            Map<String, Object> installed = installWithPip(
                    tool, PIP.get(tool), timeoutSeconds, listener);
            pipTried = true;
            Map<String, Object> done = installedTool(
                    tool, base, installed, out, listener, started);
            if (done != null) {
                return done;
            }
            errors.add(Json.str(installed, "error", "pip installation failed"));
        }

        ToolPackage spec = PACKAGES.get(tool);
        if (spec != null) {
            Map<String, Object> installed = installPackage(spec, timeoutSeconds, listener);
            if (Json.bool(installed, "ok", false)) {
                refreshPath();
                String path = findRequested(base);
                if (path == null) {
                    for (String name : requestedNames(base)) {
                        path = discoverCommand(stripExe(name));
                        if (path != null) {
                            break;
                        }
                    }
                }
                if (path != null) {
                    Map<String, Object> done = success(out, tool, path,
                            Json.str(installed, "manager", "package-manager"),
                            String.join(", ", stringList(installed.get("packages"))),
                            "installed " + spec.name + " with "
                                    + Json.str(installed, "manager", "the package manager"),
                            "", started);
                    notify(listener, tool, "ready", 100,
                            "installed " + spec.name + " · " + path);
                    return done;
                }
                errors.add("the package manager finished but " + base + " was not found");
                out.put("install_log", Json.str(installed, "log", ""));
                out.put("install_output", Json.str(installed, "output", ""));
            } else {
                errors.add(Json.str(installed, "error", "package installation failed"));
                out.put("install_log", Json.str(installed, "log", ""));
                out.put("install_output", Json.str(installed, "output", ""));
            }
        }

        if (PIP.containsKey(tool) && !pipTried) {
            Map<String, Object> installed = installWithPip(
                    tool, PIP.get(tool), timeoutSeconds, listener);
            Map<String, Object> done = installedTool(tool, base, installed, out, listener, started);
            if (done != null) {
                return done;
            }
            errors.add(Json.str(installed, "error", "pip installation failed"));
        }
        if (NPM.containsKey(tool)) {
            Map<String, Object> installed = installWithNpm(
                    tool, NPM.get(tool), timeoutSeconds, listener);
            Map<String, Object> done = installedTool(tool, base, installed, out, listener, started);
            if (done != null) {
                return done;
            }
            errors.add(Json.str(installed, "error", "npm installation failed"));
        }

        out.put("error", String.join("; ", errors));
        out.put("duration_ms", System.currentTimeMillis() - started);
        notify(listener, tool, "failed", -1, out.get("error").toString());
        return out;
    }

    private Map<String, Object> installedTool(String tool, String base,
                                              Map<String, Object> installed,
                                              Map<String, Object> out,
                                              Listener listener, long started) {
        if (!Json.bool(installed, "ok", false)) {
            out.put("install_log", Json.str(installed, "log", ""));
            out.put("install_output", Json.str(installed, "output", ""));
            return null;
        }
        refreshPath();
        String path = findRequested(base);
        if (path == null) {
            for (String name : requestedNames(base)) {
                path = discoverCommand(stripExe(name));
                if (path != null) {
                    break;
                }
            }
        }
        if (path == null) {
            out.put("install_log", Json.str(installed, "log", ""));
            out.put("install_output", Json.str(installed, "output", ""));
            return null;
        }
        String manager = Json.str(installed, "manager", "package-manager");
        String packageName = String.join(", ", stringList(installed.get("packages")));
        Map<String, Object> done = success(out, tool, path, manager,
                packageName, "installed with " + manager, "", started);
        notify(listener, tool, "ready", 100,
                "installed " + tool + " · " + path);
        return done;
    }

    private static Map<String, Object> success(Map<String, Object> out, String tool,
                                               String path, String method,
                                               String packages, String message,
                                               String version, long started) {
        out.put("ok", true);
        out.put("available", true);
        out.put("installed", true);
        out.put("path", path);
        out.put("method", method);
        out.put("packages", packages);
        out.put("message", message);
        out.put("version", version);
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    private Map<String, Object> installPackage(ToolPackage spec, int timeoutSeconds,
                                               Listener listener) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        List<String> command = new ArrayList<>();
        List<String> packages = List.of();
        String manager = "";
        if (WINDOWS) {
            String winget = findCommand("winget");
            if (winget == null) {
                winget = whereExe("winget");
            }
            if (winget == null || spec.winget.isEmpty()) {
                out.put("error", "winget is not available for " + spec.name);
                notify(listener, spec.id, "failed", -1, out.get("error").toString());
                return out;
            }
            manager = "winget";
            packages = spec.winget;
            command.add(winget);
            command.add("install");
            command.add("--id");
            command.add(spec.winget.get(0));
            command.add("-e");
            command.add("--source");
            command.add("winget");
            command.add("--accept-package-agreements");
            command.add("--accept-source-agreements");
            command.add("--disable-interactivity");
            command.add("--silent");
            if (WINGET_IGNORE_DEPENDENCIES.contains(spec.id)) {
                command.add("--skip-dependencies");
            }
        } else if (MAC) {
            String brew = findCommand("brew");
            if (brew == null) {
                out.put("error", "Homebrew is required to install " + spec.name
                        + " automatically on macOS");
                notify(listener, spec.id, "failed", -1, out.get("error").toString());
                return out;
            }
            if (spec.brew.isEmpty()) {
                out.put("error", "No Homebrew formula mapped for " + spec.name);
                notify(listener, spec.id, "failed", -1, out.get("error").toString());
                return out;
            }
            manager = "brew";
            packages = spec.brew;
            command.add(brew);
            command.add("install");
            command.addAll(spec.brew);
        } else {
            String apt = findCommand("apt-get");
            String dnf = findCommand("dnf");
            String yum = findCommand("yum");
            String pacman = findCommand("pacman");
            String zypper = findCommand("zypper");
            String apk = findCommand("apk");
            List<String> prefix = privilegePrefix();
            if (prefix == null) {
                out.put("error", "Installing " + spec.name
                        + " needs root privileges and passwordless sudo is not available");
                notify(listener, spec.id, "failed", -1, out.get("error").toString());
                return out;
            }
            if (apt != null && !spec.apt.isEmpty()) {
                manager = "apt-get";
                packages = spec.apt;
                command.addAll(prefix);
                command.add(apt);
                command.add("install");
                command.add("-y");
                command.add("--no-install-recommends");
                command.addAll(spec.apt);
            } else if (dnf != null && !spec.dnf.isEmpty()) {
                manager = "dnf";
                packages = spec.dnf;
                command.addAll(prefix);
                command.add(dnf);
                command.add("install");
                command.add("-y");
                command.addAll(spec.dnf);
            } else if (yum != null && !spec.dnf.isEmpty()) {
                manager = "yum";
                packages = spec.dnf;
                command.addAll(prefix);
                command.add(yum);
                command.add("install");
                command.add("-y");
                command.addAll(spec.dnf);
            } else if (pacman != null && !spec.pacman.isEmpty()) {
                manager = "pacman";
                packages = spec.pacman;
                command.addAll(prefix);
                command.add(pacman);
                command.add("-S");
                command.add("--noconfirm");
                command.add("--needed");
                command.addAll(spec.pacman);
            } else if (zypper != null && !spec.zypper.isEmpty()) {
                manager = "zypper";
                packages = spec.zypper;
                command.addAll(prefix);
                command.add(zypper);
                command.add("--non-interactive");
                command.add("install");
                command.addAll(spec.zypper);
            } else if (apk != null && !spec.apk.isEmpty()) {
                manager = "apk";
                packages = spec.apk;
                command.addAll(prefix);
                command.add(apk);
                command.add("add");
                command.addAll(spec.apk);
            } else {
                out.put("error", "No supported package manager found for " + spec.name);
                notify(listener, spec.id, "failed", -1, out.get("error").toString());
                return out;
            }
        }

        out.put("manager", manager);
        out.put("packages", packages);
        notify(listener, spec.id, "installing", -1,
                manager + " is installing " + String.join(", ", packages)
                        + " (this can take several minutes)");
        Map<String, Object> result = runProcess(command, timeoutSeconds);
        out.put("log", result.get("log"));
        String output = Json.str(result, "output", "");
        out.put("output", tail(output, 16000));
        boolean ok = Json.bool(result, "ok", false);
        if (!ok && "apt-get".equals(manager)) {
            notify(listener, spec.id, "installing", -1,
                    "refreshing apt package lists and retrying once");
            List<String> update = new ArrayList<>(privilegePrefix());
            update.add(findCommand("apt-get"));
            update.add("update");
            runProcess(update, Math.min(timeoutSeconds, 600));
            result = runProcess(command, timeoutSeconds);
            out.put("log", result.get("log"));
            output = Json.str(result, "output", "");
            out.put("output", tail(output, 16000));
            ok = Json.bool(result, "ok", false);
        }
        if (ok && "rust".equals(spec.id)) {
            String rustup = findCommand("rustup");
            if (rustup != null && findCommand("rustc") == null) {
                notify(listener, spec.id, "installing", -1,
                        "configuring the stable Rust toolchain");
                runProcess(List.of(rustup, "default", "stable"), Math.min(timeoutSeconds, 1800));
            }
        }
        out.put("ok", ok);
        if (!ok) {
            out.put("error", manager + " failed: " + tail(output, 800));
        }
        return out;
    }

    private Map<String, Object> installWithPip(String tool, String packageName,
                                               int timeoutSeconds, Listener listener) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("manager", "pip");
        out.put("packages", List.of(packageName));
        String python = findCommand("python");
        if (python == null) {
            python = findCommand("python3");
        }
        if (python == null) {
            notify(listener, tool, "installing", -1,
                    "Python is required for " + packageName + "; provisioning Python first");
            ensureCommand("python", Math.min(900, timeoutSeconds), listener);
            refreshPath();
            python = findCommand("python");
            if (python == null) {
                python = findCommand("python3");
            }
        }
        if (python == null) {
            out.put("error", "Python is not available for pip installation");
            return out;
        }
        Map<String, Object> pipCheck = runProcess(
                List.of(python, "-m", "pip", "--version"), 90);
        if (!Json.bool(pipCheck, "ok", false)) {
            notify(listener, tool, "installing", -1,
                    "bootstrapping pip for " + packageName);
            runProcess(List.of(python, "-m", "ensurepip", "--upgrade"), 300);
            pipCheck = runProcess(List.of(python, "-m", "pip", "--version"), 90);
        }
        if (!Json.bool(pipCheck, "ok", false)) {
            out.put("error", "pip is not available for " + python);
            out.put("output", tail(Json.str(pipCheck, "output", ""), 2000));
            return out;
        }
        notify(listener, tool, "installing", -1,
                "pip is installing " + packageName + " for the current user");
        Map<String, Object> result = runProcess(List.of(
                python, "-m", "pip", "install", "--user", "--upgrade", packageName),
                timeoutSeconds);
        out.put("log", result.get("log"));
        out.put("output", tail(Json.str(result, "output", ""), 16000));
        boolean ok = Json.bool(result, "ok", false);
        out.put("ok", ok);
        if (!ok) {
            out.put("error", "pip failed: " + tail(Json.str(result, "output", ""), 800));
        }
        return out;
    }

    private Map<String, Object> installWithNpm(String tool, String packageName,
                                               int timeoutSeconds, Listener listener) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("manager", "npm");
        out.put("packages", List.of(packageName));
        String npm = findCommand("npm");
        if (npm == null) {
            notify(listener, tool, "installing", -1,
                    "Node.js is required for " + packageName + "; provisioning Node.js first");
            ensureCommand("node", Math.min(900, timeoutSeconds), listener);
            refreshPath();
            npm = findCommand("npm");
        }
        if (npm == null) {
            out.put("error", "npm is not available for global installation");
            return out;
        }
        Path prefix = root.resolve("native").resolve("npm-global").toAbsolutePath();
        try {
            Files.createDirectories(prefix);
            Files.createDirectories(prefix.resolve("bin"));
        } catch (IOException ex) {
            out.put("error", "cannot create npm prefix: " + ex.getMessage());
            return out;
        }
        List<String> command;
        if (WINDOWS) {
            String line = quote(npm) + " install -g --prefix " + quote(prefix.toString())
                    + " " + packageName;
            command = List.of("cmd.exe", "/d", "/s", "/c", line);
        } else {
            command = List.of(npm, "install", "-g", "--prefix", prefix.toString(), packageName);
        }
        notify(listener, tool, "installing", -1,
                "npm is installing " + packageName + " into the managed prefix");
        Map<String, Object> result = runProcess(command, timeoutSeconds);
        extraDirs.add(prefix.toString());
        extraDirs.add(prefix.resolve("bin").toString());
        refreshPath();
        out.put("log", result.get("log"));
        out.put("output", tail(Json.str(result, "output", ""), 16000));
        boolean ok = Json.bool(result, "ok", false);
        out.put("ok", ok);
        if (!ok) {
            out.put("error", "npm failed: " + tail(Json.str(result, "output", ""), 800));
        }
        return out;
    }

    private static String quote(String value) {
        return value.contains(" ") ? "\"" + value + "\"" : value;
    }

    private List<String> privilegePrefix() {
        if (!WINDOWS && !MAC) {
            Map<String, Object> id = runProcess(List.of("id", "-u"), 10);
            if ("0".equals(Json.str(id, "output", "").trim())) {
                return new ArrayList<>();
            }
            Map<String, Object> sudo = runProcess(List.of("sudo", "-n", "true"), 10);
            if (Json.bool(sudo, "ok", false)) {
                return new ArrayList<>(List.of("sudo", "-n"));
            }
            return null;
        }
        return new ArrayList<>();
    }

    private Map<String, Object> runProcess(List<String> command, int timeoutSeconds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        Process process = null;
        Path log = null;
        try {
            Path logs = root.resolve("logs");
            Files.createDirectories(logs);
            log = Files.createTempFile(logs, "provision-", ".log");
            out.put("log", log.toString());
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().putAll(environment());
            builder.environment().put("DEBIAN_FRONTEND", "noninteractive");
            builder.environment().put("HOMEBREW_NO_AUTO_UPDATE", "1");
            builder.environment().put("HOMEBREW_NO_INSTALL_CLEANUP", "1");
            builder.environment().put("NONINTERACTIVE", "1");
            process = builder.start();
            process.getOutputStream().close();
            boolean finished = process.waitFor(
                    Math.max(30, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                out.put("error", "package manager timed out after " + timeoutSeconds + " seconds");
                out.put("output", tail(read(log), 4000));
                return out;
            }
            String output = read(log);
            out.put("output", output);
            out.put("exit_code", process.exitValue());
            out.put("ok", process.exitValue() == 0);
            if (process.exitValue() != 0) {
                out.put("error", "exit code " + process.exitValue());
            }
            return out;
        } catch (Exception ex) {
            out.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return out;
        } finally {
            if (process != null && process.isAlive()) {
                killTree(process);
            }
        }
    }

    private List<String> systemPathPieces() {
        long now = System.currentTimeMillis();
        List<String> cached = cachedPath;
        if (!cached.isEmpty() && now - cachedPathAt < 20_000L) {
            return cached;
        }
        synchronized (pathLock) {
            if (!cachedPath.isEmpty() && System.currentTimeMillis() - cachedPathAt < 20_000L) {
                return cachedPath;
            }
            LinkedHashSet<String> pieces = new LinkedHashSet<>();
            for (String part : splitPath(System.getenv().getOrDefault("PATH", ""))) {
                if (Files.isDirectory(Path.of(part))) {
                    addUnique(pieces, part, WINDOWS);
                }
            }
            if (WINDOWS) {
                for (String part : windowsRegistryPath()) {
                    if (Files.isDirectory(Path.of(part))) {
                        addUnique(pieces, part, WINDOWS);
                    }
                }
                for (String candidate : windowsKnownDirs()) {
                    if (candidate != null && Files.isDirectory(Path.of(candidate))) {
                        addUnique(pieces, candidate, true);
                    }
                }
                for (String candidate : wingetBinDirs()) {
                    addUnique(pieces, candidate, true);
                }
            } else {
                for (String candidate : unixKnownDirs()) {
                    if (candidate != null && Files.isDirectory(Path.of(candidate))) {
                        addUnique(pieces, candidate, false);
                    }
                }
            }
            synchronized (extraDirs) {
                for (String extra : extraDirs) {
                    if (Files.isDirectory(Path.of(extra))) {
                        addUnique(pieces, extra, WINDOWS);
                    }
                }
            }
            cachedPath = new ArrayList<>(pieces);
            cachedPathAt = System.currentTimeMillis();
            return cachedPath;
        }
    }

    private List<String> windowsRegistryPath() {
        List<String> out = new ArrayList<>();
        for (String key : List.of("HKCU\\Environment",
                "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment")) {
            try {
                Process process = new ProcessBuilder("reg.exe", "query", key, "/v", "Path")
                        .redirectErrorStream(true).start();
                if (!process.waitFor(4, TimeUnit.SECONDS)) {
                    killTree(process);
                    continue;
                }
                String text = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    int at = line.indexOf("REG_EXPAND_SZ");
                    if (at < 0) {
                        at = line.indexOf("REG_SZ");
                    }
                    if (at < 0) {
                        continue;
                    }
                    String value = line.substring(at + (line.startsWith("REG_EXPAND_SZ", at)
                            ? "REG_EXPAND_SZ".length() : "REG_SZ".length())).trim();
                    for (String part : splitPath(expand(value))) {
                        addUnique(out, part, true);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private List<String> windowsKnownDirs() {
        List<String> out = new ArrayList<>();
        String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        String programFilesX86 = System.getenv().getOrDefault("ProgramFiles(x86)",
                "C:\\Program Files (x86)");
        String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
        String home = System.getProperty("user.home", "");
        addUnique(out, Path.of(local, "Microsoft", "WinGet", "Links").toString(), true);
        addUnique(out, Path.of(local, "Microsoft", "WindowsApps").toString(), true);
        addUnique(out, Path.of(programFiles, "Git", "cmd").toString(), true);
        addUnique(out, Path.of(programFiles, "Git", "bin").toString(), true);
        addUnique(out, Path.of(programFiles, "CMake", "bin").toString(), true);
        addUnique(out, Path.of(programFiles, "LLVM", "bin").toString(), true);
        addUnique(out, Path.of(programFiles, "nodejs").toString(), true);
        addUnique(out, Path.of(programFiles, "Go", "bin").toString(), true);
        addUnique(out, Path.of(programFiles, "dotnet").toString(), true);
        addUnique(out, Path.of(programFiles, "7-Zip").toString(), true);
        addUnique(out, Path.of(programFilesX86, "7-Zip").toString(), true);
        addUnique(out, Path.of(programFiles, "GitHub CLI").toString(), true);
        addUnique(out, Path.of(programFiles, "Amazon", "AWSCLIV2").toString(), true);
        addUnique(out, Path.of(programFiles, "Microsoft SDKs", "Azure", "CLI2", "wbin")
                .toString(), true);
        addUnique(out, Path.of(local, "Google", "Cloud SDK", "google-cloud-sdk", "bin")
                .toString(), true);
        addUnique(out, Path.of(programFiles, "Tesseract-OCR").toString(), true);
        addUnique(out, Path.of(local, "Programs", "Tesseract-OCR").toString(), true);
        addUnique(out, Path.of(programFiles, "LibreOffice", "program").toString(), true);
        addUnique(out, Path.of(programFiles, "LibreOffice 7", "program").toString(), true);
        addGlob(out, Path.of(programFiles, "gs"), "gs*", "bin");
        addUnique(out, Path.of(programFiles, "qpdf", "bin").toString(), true);
        addGlob(out, Path.of(programFiles), "qpdf*", "bin");
        addUnique(out, Path.of(local, "Pandoc").toString(), true);
        addUnique(out, Path.of(local, "Programs", "yt-dlp").toString(), true);
        addUnique(out, Path.of(programFiles, "VideoLAN", "VLC").toString(), true);
        addUnique(out, Path.of(programFiles, "Mozilla Firefox").toString(), true);
        addUnique(out, Path.of(programFilesX86, "Mozilla Firefox").toString(), true);
        addUnique(out, Path.of(programFiles, "Google", "Chrome", "Application")
                .toString(), true);
        addUnique(out, Path.of(programFilesX86, "Google", "Chrome", "Application")
                .toString(), true);
        addGlob(out, Path.of(programFiles), "GIMP*", "bin");
        addUnique(out, Path.of(programFiles, "Inkscape", "bin").toString(), true);
        addUnique(out, Path.of(programFiles, "obs-studio", "bin", "64bit").toString(), true);
        addUnique(out, Path.of(local, "Programs", "Ollama").toString(), true);
        addUnique(out, Path.of(home, ".cargo", "bin").toString(), true);
        addUnique(out, Path.of(home, "go", "bin").toString(), true);
        addUnique(out, Path.of(home, "scoop", "shims").toString(), true);
        addUnique(out, Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"),
                "chocolatey", "bin").toString(), true);
        addUnique(out, Path.of(System.getenv().getOrDefault("APPDATA", ""), "npm")
                .toString(), true);
        addUnique(out, Path.of(local, "Programs", "Microsoft VS Code", "bin")
                .toString(), true);
        addUnique(out, Path.of(programFiles, "Microsoft VS Code", "bin")
                .toString(), true);
        addUnique(out, Path.of(programFiles, "Docker", "Docker", "resources", "bin")
                .toString(), true);
        addGlob(out, Path.of(programFiles, "Microsoft"), "jdk-*", "bin");
        addGlob(out, Path.of(programFiles, "Eclipse Adoptium"), "*", "bin");
        addGlob(out, Path.of(local, "Programs", "Python"), "Python*", "");
        addGlob(out, Path.of(local, "Programs", "Python"), "Python*", "Scripts");
        addGlob(out, Path.of(System.getenv().getOrDefault("APPDATA", ""), "Python"),
                "Python*", "Scripts");
        addGlob(out, Path.of(programFiles, "PostgreSQL"), "*", "bin");
        addGlob(out, Path.of(programFiles, "MySQL"), "*", "bin");
        addGlob(out, Path.of(programFiles, "MariaDB"), "*", "bin");
        addGlob(out, Path.of(programFiles, "MongoDB"), "*", "bin");
        addGlob(out, Path.of(programFiles, "Neo4j"), "*", "bin");
        addGlob(out, Path.of(programFiles, "Microsoft SQL Server"), "*", "Tools\\Binn");
        addGlob(out, Path.of(programFiles, "Microsoft SQL Server"),
                "*", "Tools\\Binn\\x64");
        addGlob(out, Path.of(programFiles, "Microsoft SQL Server"),
                "*", "Client SDK\\ODBC\\170\\Tools\\Binn");
        addGlob(out, root.resolve("native").resolve("toolchains"), "python", "Scripts");
        addUnique(out, root.resolve("native").resolve("npm-global").toString(), true);
        addUnique(out, root.resolve("native").resolve("npm-global").resolve("bin")
                .toString(), true);
        return out;
    }

    private List<String> wingetBinDirs() {
        List<String> out = new ArrayList<>();
        Path packages = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""),
                "Microsoft", "WinGet", "Packages");
        if (!Files.isDirectory(packages)) {
            return out;
        }
        try (var stream = Files.list(packages)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                for (String nested : List.of("", "bin", "mingw64\\bin", "mingw32\\bin",
                        "usr\\bin", "ffmpeg\\bin")) {
                    Path candidate = nested.isEmpty() ? dir : dir.resolve(nested);
                    if (Files.isDirectory(candidate)) {
                        boolean hasBinary = false;
                        try (var files = Files.list(candidate)) {
                            hasBinary = files.limit(200).anyMatch(path ->
                                    path.getFileName().toString().toLowerCase(Locale.ROOT)
                                            .endsWith(".exe"));
                        } catch (Exception ignored) {
                        }
                        if (hasBinary && !nested.isEmpty()) {
                            addUnique(out, candidate.toString(), true);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<String> unixKnownDirs() {
        List<String> out = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        for (String dir : List.of("/usr/local/bin", "/usr/local/sbin", "/opt/homebrew/bin",
                "/opt/homebrew/sbin", "/opt/local/bin", "/usr/local/go/bin", "/snap/bin",
                home + "/.cargo/bin", home + "/.local/bin", home + "/go/bin",
                home + "/.npm-global/bin",
                "/opt/homebrew/opt/llvm/bin", "/opt/homebrew/opt/openjdk/bin",
                "/opt/homebrew/opt/libpq/bin", "/opt/homebrew/opt/mysql-client/bin",
                "/opt/homebrew/opt/mysql-client@8.4/bin", "/opt/homebrew/opt/mariadb/bin",
                "/opt/homebrew/opt/redis/bin", "/opt/homebrew/opt/postgresql@17/bin",
                "/opt/homebrew/opt/mongodb-community/bin",
                "/usr/local/opt/llvm/bin", "/usr/local/opt/openjdk/bin",
                "/usr/local/opt/libpq/bin", "/usr/local/opt/mysql-client/bin",
                "/usr/local/opt/mariadb/bin", "/usr/local/opt/redis/bin",
                "/usr/local/opt/postgresql@17/bin",
                "/Applications/LibreOffice.app/Contents/MacOS",
                home + "/google-cloud-sdk/bin",
                home + "/Library/Android/sdk/platform-tools",
                home + "/Android/Sdk/platform-tools",
                "/usr/lib/android-sdk/platform-tools",
                "/usr/lib/google-cloud-sdk/bin",
                "/Library/TeX/texbin")) {
            addUnique(out, dir, false);
        }
        addGlob(out, Path.of("/usr/lib/postgresql"), "*", "bin");
        addGlob(out, Path.of(home, ".local", "pipx", "venvs"), "*", "bin");
        addGlob(out, Path.of(home, "Library", "Python"), "*", "bin");
        addGlob(out, root.resolve("native").resolve("toolchains"), "python", "bin");
        addUnique(out, root.resolve("native").resolve("npm-global").resolve("bin")
                .toString(), false);
        return out;
    }

    private static void addGlob(List<String> out, Path base, String pattern, String child) {
        if (!Files.isDirectory(base)) {
            return;
        }
        try (var stream = Files.list(base)) {
            for (Path dir : stream.toList()) {
                String name = dir.getFileName().toString();
                if (!name.matches(pattern.replace("*", ".*"))) {
                    continue;
                }
                Path candidate = child == null || child.isEmpty() ? dir : dir.resolve(child);
                if (Files.isDirectory(candidate)) {
                    addUnique(out, candidate.toString(), true);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String findOnPath(String name) {
        Map<String, String> env = environment();
        String path = env.getOrDefault(PATH_KEY, env.getOrDefault("PATH", ""));
        String[] extensions = WINDOWS
                ? new String[]{"", ".exe", ".cmd", ".bat", ".com"}
                : new String[]{""};
        for (String dir : splitPath(path)) {
            for (String extension : extensions) {
                try {
                    Path candidate = Path.of(dir, name + extension);
                    if (!Files.isRegularFile(candidate)) {
                        continue;
                    }
                    if (!WINDOWS && !Files.isExecutable(candidate)) {
                        continue;
                    }
                    return candidate.toString();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private String discoverCommand(String name) {
        if (!WINDOWS) {
            return null;
        }
        String executable = name + ".exe";
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""),
                "Microsoft", "WinGet", "Packages"));
        roots.add(Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files")));
        roots.add(Path.of(System.getenv().getOrDefault("ProgramFiles(x86)",
                "C:\\Program Files (x86)")));
        roots.add(Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Programs"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root, 6)) {
                Path found = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .equalsIgnoreCase(executable))
                        .findFirst().orElse(null);
                if (found != null) {
                    extraDirs.add(found.getParent().toString());
                    return found.toString();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void addUnique(java.util.Collection<String> target, String value,
                                  boolean ignoreCase) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String existing : target) {
            if (ignoreCase ? existing.equalsIgnoreCase(value) : existing.equals(value)) {
                return;
            }
        }
        target.add(value);
    }

    private static List<String> splitPath(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String trimmed = part.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String expand(String value) {
        String out = value;
        for (int pass = 0; pass < 4; pass++) {
            int open = out.indexOf('%');
            if (open < 0) {
                break;
            }
            int close = out.indexOf('%', open + 1);
            if (close < 0) {
                break;
            }
            String key = out.substring(open + 1, close);
            String replacement = System.getenv().get(key);
            if (replacement == null) {
                replacement = System.getProperty(key, "");
            }
            out = out.substring(0, open) + replacement + out.substring(close + 1);
        }
        return out;
    }

    private static String baseName(String command) {
        String value = command == null ? "" : command.trim();
        if (value.isEmpty()) {
            return "";
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        value = value.replace('/', '\\');
        int slash = value.lastIndexOf('\\');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return stripExe(value).trim();
    }

    private static String stripExe(String value) {
        String out = value == null ? "" : value.trim();
        if (out.toLowerCase(Locale.ROOT).endsWith(".exe")
                || out.toLowerCase(Locale.ROOT).endsWith(".cmd")
                || out.toLowerCase(Locale.ROOT).endsWith(".bat")) {
            out = out.substring(0, out.lastIndexOf('.'));
        }
        return out;
    }

    private static String canonical(String command) {
        String value = stripExe(baseName(command)).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        if (value.equals("py") || value.startsWith("python3") || value.equals("python")) {
            return "python";
        }
        if (value.startsWith("pip3") || value.equals("pip")) {
            return "pip";
        }
        if (value.equals("nodejs") || value.equals("node")
                || value.equals("npm") || value.equals("npx")) {
            return "node";
        }
        if (value.equals("rustc") || value.equals("cargo") || value.equals("rustup")) {
            return "rust";
        }
        if (value.equals("gofmt")) {
            return "go";
        }
        if (value.equals("javac") || value.equals("jar") || value.equals("jshell")
                || value.equals("jlink") || value.equals("jpackage")
                || value.equals("java")) {
            return "java";
        }
        if (value.equals("g++") || value.equals("cc") || value.equals("c++")
                || value.equals("cpp") || value.equals("gcc")) {
            return "gcc";
        }
        if (value.equals("clang++")) {
            return "clang";
        }
        if (value.equals("msbuild")) {
            return "dotnet";
        }
        if (value.equals("7za") || value.equals("7zr")) {
            return "7z";
        }
        if (value.equals("gmake") || value.equals("mingw32-make")) {
            return "make";
        }
        if (value.equals("magick")) {
            return "imagemagick";
        }
        if (value.equals("ffprobe")) {
            return "ffmpeg";
        }
        if (value.equals("sqlite")) {
            return "sqlite3";
        }
        return value;
    }

    private static void notify(Listener listener, String tool, String state,
                               int percent, String message) {
        if (listener != null) {
            try {
                listener.progress(tool, state, percent, message);
            } catch (Exception ignored) {
            }
        }
    }

    private static String read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static String tail(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : "…" + text.substring(text.length() - maxChars);
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static void killTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
        }
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }
}
