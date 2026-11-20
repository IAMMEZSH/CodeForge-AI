package codeforge.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds a program in any supported language and delivers a double-clickable
 * artifact to the user's Desktop. Native languages get an executable; Java gets
 * a runnable jar with a launcher; interpreted languages get a launcher that
 * pins the detected interpreter. HTML/CSS gets an index page and a shortcut.
 */
public final class BuildDelivery {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern IMPORT_EXPORT = Pattern.compile(
            "(?m)^\\s*(?:import\\s|export\\s)");
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "(?:^|\\n)\\s*package\\s+([\\p{L}_][\\p{L}\\p{N}_$.]*)\\s*;");
    private static final Pattern JAVA_PUBLIC_TYPE = Pattern.compile(
            "\\bpublic\\s+(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)?"
                    + "(?:class|interface|enum|record)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)");
    private static final Pattern JAVA_ANY_TYPE = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)");
    private static final long MAX_COPY_BYTES = 64L * 1024 * 1024;
    private static final int MAX_COPY_FILES = 500;
    private static final Pattern PY_MODULE_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final List<String> PYPI_INDEXES = List.of(
            "https://pypi.tuna.tsinghua.edu.cn/simple",
            "https://mirrors.aliyun.com/pypi/simple/",
            "https://mirrors.ustc.edu.cn/pypi/simple",
            "https://mirrors.cloud.tencent.com/pypi/simple",
            "https://repo.huaweicloud.com/repository/pypi/simple",
            "https://pypi.org/simple");
    private static final List<String> NPM_REGISTRIES = List.of(
            "https://registry.npmmirror.com",
            "https://mirrors.cloud.tencent.com/npm/",
            "https://registry.npmjs.org");
    private static final Set<String> NODE_BUILTINS = Set.of(
            "assert", "async_hooks", "buffer", "child_process", "cluster", "console",
            "constants", "crypto", "dgram", "diagnostics_channel", "dns", "domain",
            "events", "fs", "http", "http2", "https", "inspector", "module", "net",
            "os", "path", "perf_hooks", "process", "punycode", "querystring",
            "readline", "repl", "stream", "string_decoder", "sys", "test", "timers",
            "tls", "trace_events", "tty", "url", "util", "v8", "vm", "wasi",
            "worker_threads", "zlib", "fs/promises", "stream/web", "timers/promises",
            "dns/promises", "readline/promises", "util/types", "stream/consumers");
    private static final Pattern NODE_SPECIFIER = Pattern.compile(
            "(?:require\\s*\\(\\s*['\"]([^'\"]+)['\"]"
                    + "|\\bfrom\\s+['\"]([^'\"]+)['\"]"
                    + "|\\bimport\\s+['\"]([^'\"]+)['\"]"
                    + "|\\bimport\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\))");
    private static final String GO_PROXY = "https://goproxy.cn,https://proxy.golang.org,direct";
    /** Import name to PyPI project name where the two differ. */
    private static final Map<String, String> PY_PIP_NAMES = Map.ofEntries(
            Map.entry("PIL", "Pillow"),
            Map.entry("cv2", "opencv-python"),
            Map.entry("yaml", "PyYAML"),
            Map.entry("bs4", "beautifulsoup4"),
            Map.entry("sklearn", "scikit-learn"),
            Map.entry("skimage", "scikit-image"),
            Map.entry("dotenv", "python-dotenv"),
            Map.entry("dateutil", "python-dateutil"),
            Map.entry("Crypto", "pycryptodome"),
            Map.entry("serial", "pyserial"),
            Map.entry("pkg_resources", "setuptools"),
            Map.entry("OpenGL", "PyOpenGL"),
            Map.entry("wx", "wxPython"),
            Map.entry("pymysql", "PyMySQL"),
            Map.entry("mysql", "mysql-connector-python"));
    private static final String PY_IMPORT_SCAN = """
            import ast, sys
            with open(sys.argv[1], 'r', encoding='utf-8-sig') as fh:
                tree = ast.parse(fh.read(), filename=sys.argv[1])
            names = set()
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        names.add(alias.name.split('.')[0])
                elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
                    names.add(node.module.split('.')[0])
            for name in sorted(names):
                if name and name != '__future__':
                    print(name)
            """;
    private static final String PY_DEPENDENCY_PROBE = """
            import importlib.util, os, sys
            base = sys.argv[1]
            stdlib = getattr(sys, 'stdlib_module_names', set())
            for name in sys.argv[2:]:
                if name in stdlib:
                    continue
                if os.path.isfile(os.path.join(base, name + '.py')) or os.path.isdir(os.path.join(base, name)):
                    continue
                print('T ' + name)
                try:
                    found = importlib.util.find_spec(name) is not None
                except Exception:
                    found = False
                if not found:
                    print('M ' + name)
            """;

    private final Path root;
    private final Path workspace;
    private final ToolchainManager toolchains;
    /** Apps opened by the host, keyed by artifact path, so a rebuild replaces the old window. */
    private static final ConcurrentHashMap<String, Process> OPEN_APPS =
            new ConcurrentHashMap<>();
    /** Set per build() call from the user-approved auto_install flag. */
    private final ThreadLocal<Boolean> installConsent = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public BuildDelivery(Path root, ToolchainManager toolchains) {
        this.root = root;
        this.workspace = root.resolve("workspace");
        this.toolchains = toolchains;
    }

    public Map<String, Object> build(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("success", false);
        out.put("phase", "setup");
        out.put("tool", "codeforge_build");
        Path appDir = null;
        installConsent.set(Boolean.valueOf(Json.bool(request, "auto_install", false)));
        try {
            String sourceRaw = firstNonBlank(Json.str(request, "source", ""),
                    Json.str(request, "path", ""), Json.str(request, "file", ""));
            String code = Json.str(request, "code", "");
            Path source = sourceRaw.isBlank() ? null : resolveSource(sourceRaw);
            String filename = source != null ? source.getFileName().toString()
                    : Json.str(request, "filename", "");
            LanguageSupport.Language language = LanguageSupport.normalize(
                    Json.str(request, "language", "auto"), filename);
            if (language == null) {
                throw new BuildFailure("Unsupported language. Use Python, C, C++, Java, "
                        + "JavaScript, TypeScript, Go, Rust, PHP, C#, HTML or CSS", null);
            }
            if (source == null && code.isBlank()) {
                throw new BuildFailure("source path or code is required", null);
            }
            String name = cleanAppName(safeName(Json.str(request, "name", "")));
            if (name.isBlank()) {
                name = cleanAppName(safeName(source != null
                        ? stripExtension(source.getFileName().toString()) : "CodeForgeApp"));
            }
            if (name.isBlank()) {
                name = "CodeForgeApp";
            }
            boolean gui = Json.bool(request, "gui", false);
            Path destination = resolveDestination(Json.str(request, "destination", ""));
            if (Files.isRegularFile(destination)) {
                Path parent = destination.getParent();
                if (parent != null) {
                    destination = parent;
                }
            }
            Files.createDirectories(destination);
            appDir = destination.resolve(name);
            if (Files.isRegularFile(appDir)) {
                String base = name;
                int suffix = 1;
                do {
                    name = base + "-app" + (suffix > 1 ? "-" + suffix : "");
                    appDir = destination.resolve(name);
                    ++suffix;
                } while (Files.isRegularFile(appDir) && suffix <= 50);
            }
            if (Files.isRegularFile(appDir)) {
                throw new BuildFailure("The app folder path is a file: " + appDir, null);
            }
            Files.createDirectories(appDir);

            Path work = workspace.resolve("build-" + LocalDateTime.now().format(STAMP)
                    + "-" + UUID.randomUUID().toString().substring(0, 8));
            Files.createDirectories(work);
            if (source == null) {
                source = work.resolve("source" + extension(language));
                write(source, code);
            }

            out.put("name", name);
            out.put("language", language.name);
            out.put("language_id", language.id);
            out.put("source", source.toString());
            out.put("folder", appDir.toString());
            out.put("destination", destination.toString());
            out.put("gui", gui);

            switch (language.id) {
                case "c":
                case "cpp":
                    buildNative(source, language, name, appDir, work, out);
                    break;
                case "java":
                    buildJava(source, name, gui, appDir, work, out);
                    break;
                case "go":
                    buildGo(source, name, appDir, out);
                    break;
                case "rust":
                    buildRust(source, name, appDir, out);
                    break;
                case "csharp":
                    buildCsharp(source, name, gui, appDir, work, out);
                    break;
                case "python":
                    buildPython(source, name, gui, appDir, work, out);
                    break;
                case "javascript":
                case "typescript":
                    buildNode(source, language, name, appDir, out);
                    break;
                case "php":
                    buildPhp(source, name, appDir, out);
                    break;
                case "html":
                case "css":
                    buildPreview(source, language, name, appDir, request, out);
                    break;
                default:
                    throw new BuildFailure("This language cannot be delivered yet: " + language.name, null);
            }

            if (Json.bool(request, "open_after_build", false)) {
                out.put("phase", "verify");
                openDelivered(out, appDir);
            }
            out.put("ok", true);
            out.put("success", true);
            out.put("phase", "ready");
            String artifact = Json.str(out, "artifact", "");
            String shortcut = Json.str(out, "shortcut", "");
            out.put("message", "Ready" + (artifact.isBlank() ? "" : ": " + artifact)
                    + (shortcut.isBlank() ? "" : " · shortcut: " + shortcut));
        } catch (BuildFailure ex) {
            out.put("ok", false);
            out.put("success", false);
            out.put("phase", "verify".equals(Json.str(out, "phase", "")) ? "verify" : "build");
            out.put("error", ex.getMessage());
            if (ex.exec != null) {
                putExec(out, ex.exec);
            }
            if (appDir != null) {
                out.put("folder", appDir.toString());
            }
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("success", false);
            out.put("phase", "verify".equals(Json.str(out, "phase", "")) ? "verify" : "build");
            out.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            if (appDir != null) {
                out.put("folder", appDir.toString());
            }
        }
        out.put("duration_ms", System.currentTimeMillis() - started);
        Object openCommand = out.get("open_command");
        if (openCommand instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(String.valueOf(item));
            }
            out.put("open_command", commandText(parts));
        }
        installConsent.remove();
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* per-language builds                                                */
    /* ------------------------------------------------------------------ */

    private void buildNative(Path source, LanguageSupport.Language language, String name,
                             Path appDir, Path work, Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("gcc",
                "No C/C++ compiler found. Install GCC or LLVM, then retry.");
        boolean cpp = "cpp".equals(language.id);
        Path compiler = findCompiler(runtime, cpp);
        String sourceName = cpp ? "main.cpp" : "main.c";
        Path localSource = appDir.resolve(sourceName);
        copy(source, localSource);
        String exeName = name + (WINDOWS ? ".exe" : "");
        Path exe = appDir.resolve(exeName);
        List<String> command = new ArrayList<>(List.of(
                compiler.toString(), "-O2", cpp ? "-std=c++17" : "-std=c17",
                "-Wall", "-Wextra", localSource.getFileName().toString(),
                "-o", exe.getFileName().toString()));
        if (!cpp) {
            command.add("-lm");
        }
        Exec result = exec(command, runtime.environment, appDir, 180);
        if (!result.launched || result.timedOut || result.exitCode != 0 || !Files.isRegularFile(exe)) {
            throw new BuildFailure(cpp ? "C++ compilation failed" : "C compilation failed", result);
        }
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("compiler", compiler.toString());
        out.put("artifact", exe.toString());
        out.put("artifact_kind", "executable");
        out.put("stdout", tail(result.out, 8000));
        out.put("stderr", tail(result.err, 8000));
        startupCheck(List.of(exe.toString()), null, appDir, out);
        out.put("open_command", List.of(exe.toString()));
        finish(out, name, exe, "", appDir, null);
    }

    private void buildJava(Path source, String name, boolean gui,
                           Path appDir, Path work, Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("java",
                "No JDK found (javac/java). Install a JDK 17 or newer, then retry.");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        String className = javaTypeName(code);
        if (className.isBlank()) {
            className = javaIdentifier(stripExtension(source.getFileName().toString()));
        }
        if (className.isBlank()) {
            className = "Main";
        }
        String pkg = javaPackage(code);
        String fqcn = pkg.isBlank() ? className : pkg + "." + className;
        Path javaSource = appDir.resolve(className + ".java");
        write(javaSource, code);

        Path javac = sibling(runtime, "javac" + exeSuffix());
        if (javac == null) {
            javac = which("javac" + exeSuffix(), runtime.environment);
        }
        if (javac == null) {
            javac = runtime.executable;
        }
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        Exec compile = exec(List.of(javac.toString(), "-encoding", "UTF-8",
                "-d", classes.toString(), javaSource.toString()),
                runtime.environment, work, 120);
        if (!compile.launched || compile.timedOut || compile.exitCode != 0) {
            throw new BuildFailure("Java compilation failed", compile);
        }

        Path jarTool = sibling(runtime, "jar" + exeSuffix());
        if (jarTool == null) {
            jarTool = which("jar" + exeSuffix(), runtime.environment);
        }
        Path jar = appDir.resolve(name + ".jar");
        if (jarTool != null) {
            Path manifest = work.resolve("MANIFEST.MF");
            write(manifest, "Manifest-Version: 1.0\r\nMain-Class: " + fqcn + "\r\n\r\n");
            Exec pack = exec(List.of(jarTool.toString(), "cfm", jar.toString(),
                    manifest.toString(), "-C", classes.toString(), "."),
                    runtime.environment, work, 120);
            if (!pack.launched || pack.timedOut || pack.exitCode != 0 || !Files.isRegularFile(jar)) {
                throw new BuildFailure("Java packaging failed", pack);
            }
        } else {
            Path localClasses = appDir.resolve("classes");
            copyDirectory(classes, localClasses);
        }
        Path javaExe = sibling(runtime, "java" + exeSuffix());
        if (javaExe == null) {
            javaExe = which("java" + exeSuffix(), runtime.environment);
        }
        if (javaExe == null) {
            javaExe = runtime.executable;
        }
        String runCommand = Files.isRegularFile(jar)
                ? "\"" + cmdQuote(javaExe.toString()) + "\" " + javaFlags(gui) + "-jar \""
                + cmdQuote(jar.getFileName().toString()) + "\""
                : "\"" + cmdQuote(javaExe.toString()) + "\" " + javaFlags(gui) + "-cp \"classes\" \""
                + cmdQuote(fqcn) + "\"";
        Path launcher = writeCmd(appDir, name + ".cmd",
                runCommand, !gui);
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("compiler", javac.toString());
        out.put("artifact", Files.isRegularFile(jar) ? jar.toString()
                : appDir.resolve("classes").toString());
        out.put("artifact_kind", Files.isRegularFile(jar) ? "jar" : "classes");
        out.put("launcher", launcher.toString());
        out.put("stdout", tail(compile.out, 8000));
        out.put("stderr", tail(compile.err, 8000));
        List<String> startup = new ArrayList<>();
        startup.add(javaExe.toString());
        startup.add("-Dfile.encoding=UTF-8");
        if (Files.isRegularFile(jar)) {
            startup.add("-jar");
            startup.add(jar.getFileName().toString());
        } else {
            startup.add("-cp");
            startup.add("classes");
            startup.add(fqcn);
        }
        startupCheck(startup, null, appDir, out);
        Path openJava = gui ? sibling(runtime, "javaw" + exeSuffix()) : null;
        if (openJava == null) {
            openJava = javaExe;
        }
        List<String> openCommand = new ArrayList<>();
        openCommand.add(openJava.toString());
        openCommand.add("-Dfile.encoding=UTF-8");
        if (Files.isRegularFile(jar)) {
            openCommand.add("-jar");
            openCommand.add(jar.toString());
        } else {
            openCommand.add("-cp");
            openCommand.add(appDir.resolve("classes").toString());
            openCommand.add(fqcn);
        }
        out.put("open_command", openCommand);
        finish(out, name, launcher, "", appDir, null);
    }

    private void buildGo(Path source, String name, Path appDir,
                         Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("go",
                "Go is not installed. Install Go and retry.");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        String exeName = name + (WINDOWS ? ".exe" : "");
        Path localSource = appDir.resolve("main.go");
        write(localSource, code);
        Path exe = appDir.resolve(exeName);
        Map<String, String> goEnv = new LinkedHashMap<>(runtime.environment);
        String configuredProxy = System.getenv("GOPROXY");
        if (configuredProxy == null || configuredProxy.isBlank()) {
            goEnv.put("GOPROXY", GO_PROXY);
        }
        String configuredSumdb = System.getenv("GOSUMDB");
        if (configuredSumdb == null || configuredSumdb.isBlank()) {
            goEnv.put("GOSUMDB", "sum.golang.google.cn");
        }
        List<String> requirements = scanGoImports(code);
        List<String> installed = new ArrayList<>();
        if (!requirements.isEmpty()) {
            if (!Files.isRegularFile(appDir.resolve("go.mod"))) {
                Exec init = exec(List.of(runtime.executable.toString(), "mod", "init",
                        "codeforgeapp"), goEnv, appDir, 60);
                if (!init.launched || init.timedOut || init.exitCode != 0) {
                    throw new BuildFailure("Go module initialization failed", init);
                }
            }
            List<String> get = new ArrayList<>(List.of(runtime.executable.toString(), "get"));
            get.addAll(requirements);
            Exec deps = exec(get, goEnv, appDir, 300);
            if (!deps.launched || deps.timedOut || deps.exitCode != 0) {
                throw new BuildFailure("Go dependency installation failed for "
                        + String.join(", ", requirements), deps);
            }
            installed.addAll(requirements);
        }
        Exec build = exec(List.of(runtime.executable.toString(), "build", "-trimpath",
                "-o", exeName, "main.go"), goEnv, appDir, 240);
        if (!build.launched || build.timedOut || build.exitCode != 0 || !Files.isRegularFile(exe)) {
            throw new BuildFailure("Go compilation failed", build);
        }
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("compiler", runtime.executable.toString());
        out.put("artifact", exe.toString());
        out.put("artifact_kind", "executable");
        out.put("dependencies", requirements);
        out.put("dependencies_installed", installed);
        out.put("stdout", tail(build.out, 8000));
        out.put("stderr", tail(build.err, 8000));
        startupCheck(List.of(exe.toString()), goEnv, appDir, out);
        out.put("open_command", List.of(exe.toString()));
        finish(out, name, exe, "", appDir, null);
    }

    private void buildRust(Path source, String name, Path appDir,
                           Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("rust",
                "Rust is not installed. Install Rust and retry.");
        Path localSource = appDir.resolve("main.rs");
        copy(source, localSource);
        Path exe = appDir.resolve(name + (WINDOWS ? ".exe" : ""));
        Exec compile = exec(List.of(runtime.executable.toString(), "-O", "main.rs",
                "-o", exe.getFileName().toString()), runtime.environment, appDir, 240);
        if (!compile.launched || compile.timedOut || compile.exitCode != 0
                || !Files.isRegularFile(exe)) {
            throw new BuildFailure("Rust compilation failed", compile);
        }
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("compiler", runtime.executable.toString());
        out.put("artifact", exe.toString());
        out.put("artifact_kind", "executable");
        out.put("stdout", tail(compile.out, 8000));
        out.put("stderr", tail(compile.err, 8000));
        startupCheck(List.of(exe.toString()), null, appDir, out);
        out.put("open_command", List.of(exe.toString()));
        finish(out, name, exe, "", appDir, null);
    }

    private void buildCsharp(Path source, String name, boolean gui,
                             Path appDir, Path work, Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("dotnet",
                "The .NET SDK is not installed. Install it and retry.");
        Path project = work.resolve("csharp");
        Files.createDirectories(project);
        write(project.resolve("Program.cs"), Files.readString(source, StandardCharsets.UTF_8));
        String major = runtime.version == null ? "" : runtime.version.trim().split("\\.")[0];
        String tfm = major.matches("\\d+") ? "net" + major + ".0" : "net8.0";
        String rid = WINDOWS ? ("arm64".equals(arch()) ? "win-arm64" : "win-x64")
                : MAC ? ("arm64".equals(arch()) ? "osx-arm64" : "osx-x64")
                : ("arm64".equals(arch()) ? "linux-arm64" : "linux-x64");
        String csproj = """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>%s</OutputType>
                    <TargetFramework>%s</TargetFramework>
                    <AssemblyName>%s</AssemblyName>
                    <RootNamespace>CodeForge</RootNamespace>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                    <PublishSingleFile>true</PublishSingleFile>
                    <SelfContained>false</SelfContained>
                    <RuntimeIdentifier>%s</RuntimeIdentifier>
                    <DebugType>none</DebugType>
                    <DebugSymbols>false</DebugSymbols>
                  </PropertyGroup>
                </Project>
                """.formatted(gui ? "WinExe" : "Exe", tfm, xml(name), rid);
        Path csprojPath = project.resolve("CodeForge.csproj");
        write(csprojPath, csproj);
        Exec publish = exec(List.of(runtime.executable.toString(), "publish",
                csprojPath.toString(), "-c", "Release", "-r", rid,
                "--self-contained", "false", "-p:PublishSingleFile=true",
                "-o", appDir.toString(), "--nologo", "-v", "minimal"),
                runtime.environment, project, 300);
        if (!publish.launched || publish.timedOut || publish.exitCode != 0) {
            throw new BuildFailure("C# build failed", publish);
        }
        Path exe = findExecutable(appDir, name);
        if (exe == null) {
            throw new BuildFailure("C# build finished but no executable was found in " + appDir,
                    publish);
        }
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("compiler", runtime.executable.toString());
        out.put("artifact", exe.toString());
        out.put("artifact_kind", "executable");
        out.put("stdout", tail(publish.out, 8000));
        out.put("stderr", tail(publish.err, 8000));
        startupCheck(List.of(exe.toString()), null, appDir, out);
        out.put("open_command", List.of(exe.toString()));
        finish(out, name, exe, "", appDir, null);
    }

    private void buildPython(Path source, String name, boolean gui,
                             Path appDir, Path work, Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("python",
                "Python is not installed. Install Python 3 and retry.");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        Path script = appDir.resolve(name + (gui ? ".pyw" : ".py"));
        write(script, code);
        Exec check = exec(List.of(runtime.executable.toString(), "-X", "utf8", "-m",
                "py_compile", script.toString()), runtime.environment, appDir, 60);
        if (!check.launched || check.timedOut || check.exitCode != 0) {
            throw new BuildFailure("Python syntax check failed", check);
        }
        deleteTree(appDir.resolve("__pycache__"));

        List<String> modules = scanPythonImports(runtime, script, work);
        List<String> thirdParty = new ArrayList<>();
        List<String> missing = probePythonModules(runtime, appDir, modules, work, thirdParty);
        List<String> installed = new ArrayList<>();
        for (String module : missing) {
            if ("tkinter".equals(module)) {
                throw new BuildFailure("This Python has no tkinter. Install the official "
                        + "Python from python.org (it includes tkinter), then retry.", null);
            }
            String packageName = PY_PIP_NAMES.getOrDefault(module, module);
            Exec pip = pipInstall(runtime, packageName, work);
            if (pip.launched && !pip.timedOut && pip.exitCode == 0) {
                installed.add(packageName);
            } else {
                throw new BuildFailure("The app imports \"" + module + "\", which is missing, "
                        + "and installing " + packageName + " failed. Install it with: "
                        + runtime.executable + " -m pip install " + packageName, pip);
            }
        }
        if (!missing.isEmpty()) {
            List<String> refreshed = new ArrayList<>();
            List<String> stillMissing = probePythonModules(runtime, appDir, modules, work, refreshed);
            for (String module : refreshed) {
                if (!thirdParty.contains(module)) {
                    thirdParty.add(module);
                }
            }
            if (!stillMissing.isEmpty()) {
                throw new BuildFailure("Missing Python modules after installation: "
                        + String.join(", ", stillMissing), null);
            }
        }
        if (!thirdParty.isEmpty()) {
            StringBuilder requirements = new StringBuilder();
            for (String module : thirdParty) {
                requirements.append(PY_PIP_NAMES.getOrDefault(module, module)).append('\n');
            }
            Path requirementsFile = write(appDir.resolve("requirements.txt"),
                    requirements.toString());
            out.put("requirements", requirementsFile.toString());
        }
        out.put("dependencies", thirdParty);
        out.put("dependencies_installed", installed);

        Map<String, String> smokeEnv = new LinkedHashMap<>(runtime.environment);
        String lowerCode = code.toLowerCase(Locale.ROOT);
        if (lowerCode.contains("pyside") || lowerCode.contains("pyqt")) {
            smokeEnv.put("QT_QPA_PLATFORM", "offscreen");
        }
        if (lowerCode.contains("pygame")) {
            smokeEnv.put("SDL_VIDEODRIVER", "dummy");
            smokeEnv.put("SDL_AUDIODRIVER", "dummy");
        }
        Exec smoke = exec(List.of(runtime.executable.toString(), "-X", "utf8", "-u",
                script.getFileName().toString()), smokeEnv, appDir, 10);
        out.put("startup_check_command", smoke.command);
        if (!smoke.launched) {
            throw new BuildFailure("The Python app could not be started for a startup check: "
                    + smoke.error, smoke);
        }
        if (!smoke.timedOut && smoke.exitCode != 0) {
            throw new BuildFailure("The Python app crashed during the startup check (exit "
                    + smoke.exitCode + "). Fix the runtime error and retry.", smoke);
        }
        out.put("startup_check", smoke.timedOut ? "started" : "exited-cleanly");

        Path python = runtime.executable;
        List<String> openCommand = new ArrayList<>();
        openCommand.add((gui ? pythonForGui(runtime) : python).toString());
        if (!gui) {
            openCommand.add("-X");
            openCommand.add("utf8");
            openCommand.add("-u");
        }
        openCommand.add(script.toString());
        out.put("open_command", openCommand);
        String command;
        if (gui) {
            Path pythonw = sibling(runtime, "pythonw.exe");
            if (pythonw != null) {
                command = "\"" + cmdQuote(pythonw.toString()) + "\" \"" + cmdQuote(script.getFileName().toString()) + "\"";
            } else {
                command = "\"" + cmdQuote(python.toString()) + "\" \"" + cmdQuote(script.getFileName().toString()) + "\"";
            }
        } else {
            command = "\"" + cmdQuote(python.toString())
                    + "\" -X utf8 -u \"" + cmdQuote(script.getFileName().toString()) + "\"";
        }
        Path launcher = gui ? null : writeCmd(appDir, name + ".cmd", command, true);
        if (gui) {
            Path diagnostic = writeCmd(appDir, name + "-启动诊断.cmd",
                    "\"" + cmdQuote(python.toString()) + "\" -X utf8 -u \""
                            + cmdQuote(script.getFileName().toString()) + "\"", true);
            out.put("diagnostic_launcher", diagnostic.toString());
        }
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("artifact", script.toString());
        out.put("artifact_kind", "python");
        if (launcher != null) {
            out.put("launcher", launcher.toString());
        }
        out.put("stdout", tail(check.out, 4000));
        out.put("stderr", tail(check.err, 4000));
        finish(out, name, launcher != null ? launcher : script, "", appDir,
                gui ? new ShortcutTarget(pythonForGui(runtime), "\"" + script + "\"", false) : null);
    }

    /**
     * External Go module paths (they contain a domain) from import statements.
     * Standard-library paths have no dot in their first segment.
     */
    private List<String> scanGoImports(String code) {
        List<String> found = new ArrayList<>();
        boolean block = false;
        for (String raw : code.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("import (")) {
                block = true;
                continue;
            }
            if (block && line.startsWith(")")) {
                block = false;
                continue;
            }
            if (!block && !line.startsWith("import ")) {
                continue;
            }
            Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(line);
            while (quoted.find()) {
                String path = quoted.group(1);
                int slash = path.indexOf('/');
                if (slash <= 0 || !path.substring(0, slash).contains(".")) {
                    continue;
                }
                if (!found.contains(path)) {
                    found.add(path);
                }
            }
        }
        return found;
    }

    /**
     * Opens the finished app for a real check and leaves it running when it
     * works. A GUI app that crashes or never shows a window is a failed
     * delivery: the captured runtime error goes back to the repair loop.
     */
    private void openDelivered(Map<String, Object> out, Path appDir) throws BuildFailure {
        String artifactKind = Json.str(out, "artifact_kind", "");
        out.put("opened", false);
        if ("web".equals(artifactKind)) {
            Path index = Path.of(Json.str(out, "artifact", ""));
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(
                        java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(index.toFile());
                } else {
                    Exec open = exec(WINDOWS
                            ? List.of("rundll32.exe", "url.dll,FileProtocolHandler",
                            index.toString())
                            : MAC ? List.of("open", index.toString())
                            : List.of("xdg-open", index.toString()), null, appDir, 20);
                    if (!open.launched || (!open.timedOut && open.exitCode != 0)) {
                        throw new BuildFailure("The page was built but could not be "
                                + "opened in a browser: "
                                + firstNonBlank(open.err, open.error, "unknown error"), open);
                    }
                }
                out.put("opened", true);
                out.put("open_check", "browser");
                out.put("open_command", "open " + index);
                return;
            } catch (BuildFailure ex) {
                out.put("open_error", ex.getMessage());
                throw ex;
            } catch (Exception ex) {
                out.put("open_error", ex.getMessage());
                throw new BuildFailure("The page was built but could not be opened "
                        + "in a browser: " + ex.getMessage(), null);
            }
        }
        if (!Json.bool(out, "gui", false)) {
            out.put("open_check", "startup-verified");
            return;
        }
        List<String> command = stringList(out.get("open_command"));
        if (command.isEmpty()) {
            throw new BuildFailure("The app was built, but its launch command is missing.", null);
        }
        Path log = root.resolve("logs").resolve("open-"
                + LocalDateTime.now().format(STAMP) + "-"
                + UUID.randomUUID().toString().substring(0, 6) + ".log");
        try {
            Files.createDirectories(log.getParent());
        } catch (IOException ignored) {
        }
        String artifact = Json.str(out, "artifact", "");
        Process previous = OPEN_APPS.remove(artifact);
        if (previous != null && previous.isAlive()) {
            previous.descendants().forEach(ProcessHandle::destroyForcibly);
            previous.destroyForcibly();
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(appDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(
                WINDOWS ? new File("NUL") : new File("/dev/null")));
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            out.put("open_error", ex.getMessage());
            throw new BuildFailure("The app was built but could not be opened: "
                    + ex.getMessage(), null);
        }
        OPEN_APPS.put(artifact, process);
        out.put("opened_pid", process.pid());
        out.put("open_log", log.toString());
        String probe;
        if (WINDOWS) {
            probe = waitForWindow(process.pid());
        } else {
            try {
                probe = process.waitFor(6, TimeUnit.SECONDS) ? "EXITED" : "ALIVE";
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                probe = "ALIVE";
            }
        }
        if (probe.startsWith("OK\t")) {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (!process.isAlive()) {
                probe = "EXITED";
            }
        }
        String logTail = tail(readText(log), 6000);
        if (probe.startsWith("OK\t")) {
            out.put("opened", true);
            out.put("open_check", "window-open");
            String title = probe.substring(3).strip();
            if (!title.isBlank()) {
                out.put("window_title", title);
            }
            return;
        }
        if (probe.startsWith("PROBE-ERROR") && process.isAlive()) {
            out.put("opened", true);
            out.put("open_check", "process-alive");
            out.put("open_warning", "The app process is running, but the window probe "
                    + "failed: " + probe);
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        OPEN_APPS.remove(artifact, process);
        out.put("open_error", probe.startsWith("NOWINDOW")
                ? "The app process is running, but no visible window appeared within 15 seconds."
                : "The app exited before its window appeared.");
        Exec failure = new Exec();
        failure.launched = true;
        failure.command = commandText(command);
        failure.exitCode = probe.startsWith("NOWINDOW") ? -1 : process.exitValue();
        failure.err = logTail;
        failure.error = Json.str(out, "open_error", "");
        throw new BuildFailure(Json.str(out, "open_error", "The app did not open."),
                failure);
    }

    /** Waits for the real main window and returns OK/TAB/title, EXITED, NOWINDOW or an error. */
    private String waitForWindow(long pid) {
        Path script = root.resolve("logs").resolve("open-probe-"
                + UUID.randomUUID().toString().substring(0, 8) + ".ps1");
        try {
            Files.createDirectories(script.getParent());
            String ps = "$ErrorActionPreference = 'SilentlyContinue'\n"
                    + "$p = Get-Process -Id " + pid + "\n"
                    + "if ($null -eq $p) { Write-Output 'EXITED'; exit 0 }\n"
                    + "$deadline = (Get-Date).AddSeconds(15)\n"
                    + "while ((Get-Date) -lt $deadline) {\n"
                    + "  $p.Refresh()\n"
                    + "  if ($p.HasExited) { Write-Output 'EXITED'; exit 0 }\n"
                    + "  if ($p.MainWindowHandle -ne 0) {\n"
                    + "    Write-Output ('OK' + [char]9 + $p.MainWindowTitle)\n"
                    + "    exit 0\n"
                    + "  }\n"
                    + "  Start-Sleep -Milliseconds 300\n"
                    + "}\n"
                    + "Write-Output 'NOWINDOW'\n";
            Files.writeString(script, ps, StandardCharsets.UTF_8);
            Exec probe = exec(List.of("powershell.exe", "-NoLogo", "-NoProfile",
                    "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString()), Map.of(), root, 25);
            for (String line : probe.out.split("\\R")) {
                String value = line.strip();
                if (value.startsWith("OK\t") || "EXITED".equals(value)
                        || "NOWINDOW".equals(value)) {
                    return value;
                }
            }
            return "PROBE-ERROR " + (probe.out.isBlank() ? probe.error : probe.out.strip());
        } catch (Exception ex) {
            return "PROBE-ERROR " + ex.getMessage();
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException ignored) {
            }
        }
    }

    private static List<String> stringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            values.add(String.valueOf(raw));
        }
        return values;
    }

    private static String commandText(List<String> command) {
        StringBuilder text = new StringBuilder();
        for (String part : command) {
            if (part == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            if (part.indexOf(' ') >= 0 || part.indexOf('\t') >= 0) {
                text.append('"').append(part.replace("\"", "\\\"")).append('"');
            } else {
                text.append(part);
            }
        }
        return text.toString();
    }

    private static String readText(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Encodings.decode(Files.readAllBytes(file)) : "";
        } catch (Exception ex) {
            return "";
        }
    }

    /** A short real launch that catches import failures and instant crashes. */
    private void startupCheck(List<String> command, Map<String, String> extraEnv,
                              Path workdir, Map<String, Object> out) throws BuildFailure {
        Exec run = exec(command, extraEnv, workdir, 8);
        out.put("startup_check_command", run.command);
        if (!run.launched) {
            throw new BuildFailure("The app could not be started for a startup check: "
                    + run.error, run);
        }
        if (!run.timedOut && run.exitCode != 0) {
            throw new BuildFailure("The app exited with code " + run.exitCode
                    + " during the startup check. Fix the runtime error and retry.", run);
        }
        out.put("startup_check", run.timedOut ? "started" : "exited-cleanly");
    }

    /** Top-level module names imported anywhere in the generated script. */
    private List<String> scanPythonImports(ToolchainManager.Resolved runtime,
                                           Path script, Path work) throws IOException {
        Path scanner = write(work.resolve("cf-import-scan.py"), PY_IMPORT_SCAN);
        Exec scan = exec(List.of(runtime.executable.toString(), "-X", "utf8",
                scanner.toString(), script.toString()), runtime.environment, work, 60);
        List<String> modules = new ArrayList<>();
        if (!scan.launched || scan.timedOut || scan.exitCode != 0) {
            return modules;
        }
        for (String line : scan.out.split("\\R")) {
            String module = line.strip();
            if (PY_MODULE_NAME.matcher(module).matches() && !modules.contains(module)) {
                modules.add(module);
            }
        }
        return modules;
    }

    /**
     * Splits imports into third-party (printed as "T name") and not importable
     * ("M name"). Anything that cannot be probed is treated as unknown so a
     * broken probe never triggers bogus pip installs.
     */
    private List<String> probePythonModules(ToolchainManager.Resolved runtime,
                                            Path appDir, List<String> modules,
                                            Path work, List<String> thirdParty)
            throws IOException {
        List<String> missing = new ArrayList<>();
        if (modules.isEmpty()) {
            return missing;
        }
        Path probe = write(work.resolve("cf-dependency-probe.py"), PY_DEPENDENCY_PROBE);
        List<String> command = new ArrayList<>(List.of(runtime.executable.toString(),
                "-X", "utf8", probe.toString(), appDir.toString()));
        command.addAll(modules);
        Exec result = exec(command, runtime.environment, work, 120);
        if (!result.launched || result.timedOut || result.exitCode != 0) {
            return missing;
        }
        for (String line : result.out.split("\\R")) {
            String entry = line.strip();
            String module = entry.length() > 2 ? entry.substring(2).strip() : "";
            if (!PY_MODULE_NAME.matcher(module).matches()) {
                continue;
            }
            if (entry.startsWith("T ") && !thirdParty.contains(module)) {
                thirdParty.add(module);
            } else if (entry.startsWith("M ") && !missing.contains(module)) {
                missing.add(module);
            }
        }
        return missing;
    }

    /** Installs one package, mirror first unless the user configured an index. */
    private Exec pipInstall(ToolchainManager.Resolved runtime, String packageName,
                            Path work) {
        Exec first = pipInstallAttempt(runtime, packageName, work, false);
        if (first.launched && !first.timedOut && first.exitCode == 0) {
            return first;
        }
        String text = (first.err + " " + first.out).toLowerCase(Locale.ROOT);
        boolean permissionProblem = text.contains("permission")
                || text.contains("access is denied") || text.contains("not writeable")
                || text.contains("externally-managed") || text.contains("virtualenv")
                || text.contains("--user");
        if (permissionProblem) {
            Exec perUser = pipInstallAttempt(runtime, packageName, work, true);
            if (perUser.launched && !perUser.timedOut && perUser.exitCode == 0) {
                return perUser;
            }
            return perUser;
        }
        return first;
    }

    private Exec pipInstallAttempt(ToolchainManager.Resolved runtime, String packageName,
                                   Path work, boolean perUser) {
        List<String> command = new ArrayList<>(List.of(
                runtime.executable.toString(), "-X", "utf8", "-m", "pip", "install",
                "--upgrade", "--disable-pip-version-check", "--no-input",
                "--prefer-binary", "--timeout", "45", "--retries", "1"));
        if (perUser) {
            command.add("--user");
        }
        command.add(packageName);
        String configuredIndex = System.getenv("PIP_INDEX_URL");
        if (configuredIndex == null || configuredIndex.isBlank()) {
            for (String index : PYPI_INDEXES) {
                List<String> mirrored = new ArrayList<>(command);
                mirrored.addAll(mirrored.size() - 1, List.of("-i", index));
                Exec viaMirror = exec(mirrored, runtime.environment, work, 240);
                if (viaMirror.launched && !viaMirror.timedOut && viaMirror.exitCode == 0) {
                    return viaMirror;
                }
            }
        }
        return exec(command, runtime.environment, work, 240);
    }

    private void buildNode(Path source, LanguageSupport.Language language, String name,
                           Path appDir, Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("node",
                "Node.js is not installed. Install Node 22.6 or newer and retry.");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        boolean typescript = "typescript".equals(language.id);
        String ext = typescript ? ".ts" : ".js";
        Path script = appDir.resolve(name + ext);
        write(script, code);
        if (IMPORT_EXPORT.matcher(code).find()) {
            write(appDir.resolve("package.json"), "{\"type\":\"module\"}\n");
        }
        int[] version = parseVersion(runtime.version);
        String flag = "";
        if (typescript) {
            if (version[0] > 23 || (version[0] == 23 && version[1] >= 6)) {
                flag = "";
            } else if (version[0] == 22 && version[1] >= 6) {
                flag = " --experimental-strip-types";
            } else {
                throw new BuildFailure("Node " + runtime.version + " cannot run TypeScript. "
                        + "Install Node 22.6 or newer, then retry.", null);
            }
        } else {
            Exec check = exec(List.of(runtime.executable.toString(), "--check",
                    script.toString()), runtime.environment, appDir, 60);
            if (!check.launched || check.timedOut || check.exitCode != 0) {
                throw new BuildFailure("JavaScript syntax check failed", check);
            }
        }
        List<String> requirements = scanNodePackages(code);
        List<String> missing = new ArrayList<>();
        for (String packageName : requirements) {
            if (!Files.isDirectory(appDir.resolve("node_modules").resolve(packageName))) {
                missing.add(packageName);
            }
        }
        List<String> installed = new ArrayList<>();
        if (!missing.isEmpty()) {
            Path npm = sibling(runtime, "npm.cmd", "npm");
            Path npmCli = runtime.binDir == null ? null
                    : runtime.binDir.resolve("node_modules/npm/bin/npm-cli.js");
            for (String packageName : missing) {
                Exec npmResult = npmInstall(runtime.executable, npmCli, npm, appDir, packageName);
                if (!(npmResult.launched && !npmResult.timedOut && npmResult.exitCode == 0)
                        || !Files.isDirectory(appDir.resolve("node_modules").resolve(packageName))) {
                    throw new BuildFailure("The app imports \"" + packageName
                            + "\", which is missing, and installing it failed.", npmResult);
                }
                installed.add(packageName);
            }
        }
        out.put("dependencies", requirements);
        out.put("dependencies_installed", installed);

        String command = "\"" + cmdQuote(runtime.executable.toString()) + "\"" + flag
                + " \"" + cmdQuote(script.getFileName().toString()) + "\"";
        Path launcher = writeCmd(appDir, name + ".cmd", command, true);
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("artifact", script.toString());
        out.put("artifact_kind", typescript ? "typescript" : "javascript");
        out.put("launcher", launcher.toString());
        List<String> startup = new ArrayList<>();
        startup.add(runtime.executable.toString());
        if (!flag.isBlank()) {
            startup.add(flag.trim());
        }
        startup.add(script.getFileName().toString());
        startupCheck(startup, null, appDir, out);
        out.put("open_command", new ArrayList<>(startup));
        finish(out, name, launcher, "", appDir, null);
    }

    private List<String> scanNodePackages(String code) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = NODE_SPECIFIER.matcher(code);
        while (matcher.find()) {
            String specifier = null;
            for (int i = 1; i <= matcher.groupCount(); ++i) {
                if (matcher.group(i) != null) {
                    specifier = matcher.group(i);
                    break;
                }
            }
            if (specifier == null || specifier.startsWith(".") || specifier.startsWith("/")
                    || specifier.startsWith("\\") || specifier.contains("://")
                    || specifier.startsWith("node:")) {
                continue;
            }
            String packageName;
            if (specifier.startsWith("@")) {
                String[] parts = specifier.split("/");
                if (parts.length < 2 || parts[0].length() < 2) {
                    continue;
                }
                packageName = parts[0] + "/" + parts[1];
            } else {
                packageName = specifier.split("/")[0];
            }
            if (NODE_BUILTINS.contains(specifier) || NODE_BUILTINS.contains(packageName)) {
                continue;
            }
            if (!packageName.matches("@?[A-Za-z0-9_.~-]+(?:/[A-Za-z0-9_.~-]+)?")) {
                continue;
            }
            found.add(packageName);
        }
        return new ArrayList<>(found);
    }

    private Exec npmInstall(Path nodeExe, Path npmCli, Path npm, Path appDir,
                            String packageName) throws IOException {
        Path manifest = appDir.resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            write(manifest, "{\n  \"name\": \"codeforge-app\",\n  \"private\": true\n}\n");
        }
        Exec last = null;
        for (String registry : NPM_REGISTRIES) {
            List<String> command = new ArrayList<>();
            if (npmCli != null && Files.isRegularFile(npmCli)) {
                command.add(nodeExe.toString());
                command.add(npmCli.toString());
            } else if (npm != null) {
                if (WINDOWS) {
                    command.add("cmd.exe");
                    command.add("/c");
                }
                command.add(npm.toString());
            } else {
                break;
            }
            command.add("install");
            command.add("--no-audit");
            command.add("--no-fund");
            command.add("--loglevel=error");
            command.add("--registry");
            command.add(registry);
            command.add(packageName);
            last = exec(command, null, appDir, 300);
            if (last.launched && !last.timedOut && last.exitCode == 0) {
                return last;
            }
        }
        if (last == null) {
            last = new Exec();
            last.error = "npm is not available for this Node runtime";
        }
        return last;
    }

    private void buildPhp(Path source, String name, Path appDir,
                          Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("php",
                "PHP is not installed. Install PHP and retry.");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        if (!code.stripLeading().startsWith("<?php")) {
            code = "<?php\n" + code;
        }
        Path script = appDir.resolve(name + ".php");
        write(script, code);
        Exec check = exec(List.of(runtime.executable.toString(), "-l", script.toString()),
                runtime.environment, appDir, 60);
        if (!check.launched || check.timedOut || check.exitCode != 0) {
            throw new BuildFailure("PHP syntax check failed", check);
        }
        String command = "\"" + cmdQuote(runtime.executable.toString()) + "\" \""
                + cmdQuote(script.getFileName().toString()) + "\"";
        Path launcher = writeCmd(appDir, name + ".cmd", command, true);
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("artifact", script.toString());
        out.put("artifact_kind", "php");
        out.put("launcher", launcher.toString());
        startupCheck(List.of(runtime.executable.toString(),
                script.getFileName().toString()), null, appDir, out);
        out.put("open_command", List.of(runtime.executable.toString(),
                script.toString()));
        finish(out, name, launcher, "", appDir, null);
    }

    private void buildPreview(Path source, LanguageSupport.Language language, String name,
                              Path appDir, Map<String, Object> request,
                              Map<String, Object> out) throws Exception {
        Path index = appDir.resolve("index.html");
        if (Files.isDirectory(source)) {
            copyDirectory(source, appDir);
            if (!Files.isRegularFile(index)) {
                Path found = findFile(appDir, "index.html");
                if (found == null) {
                    throw new BuildFailure("The web folder has no index.html", null);
                }
                index = found;
            }
        } else {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            String css = Json.str(request, "css", "");
            if ("css".equals(language.id)) {
                String html = Json.str(request, "html", "");
                write(appDir.resolve("style.css"), code);
                write(index, "<!doctype html>\n<html><head><meta charset=\"utf-8\">"
                        + "<link rel=\"stylesheet\" href=\"style.css\"></head><body>"
                        + (html.isBlank() ? "" : html) + "</body></html>\n");
            } else {
                write(index, code);
                if (!css.isBlank()) {
                    write(appDir.resolve("style.css"), css);
                    String page = Files.readString(index, StandardCharsets.UTF_8);
                    if (!page.toLowerCase(Locale.ROOT).contains("style.css")) {
                        page = page.replaceFirst("(?i)</head>",
                                "<link rel=\"stylesheet\" href=\"style.css\"></head>");
                        write(index, page);
                    }
                }
            }
        }
        out.put("runtime", "Browser");
        out.put("artifact", index.toString());
        out.put("artifact_kind", "web");
        finish(out, name, index, "", appDir, null);
    }

    /* ------------------------------------------------------------------ */
    /* delivery                                                           */
    /* ------------------------------------------------------------------ */

    private void finish(Map<String, Object> out, String name, Path target,
                        String args, Path appDir, ShortcutTarget override) {
        Path shortcut = null;
        try {
            if (override != null) {
                shortcut = createShortcut(name, override.target, override.arguments,
                        appDir, false);
            } else if (target != null) {
                boolean exe = target.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(".exe");
                shortcut = createShortcut(name, target, args, appDir, exe);
            }
        } catch (Exception ex) {
            out.put("shortcut_error", ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        if (shortcut != null) {
            out.put("shortcut", shortcut.toString());
        } else if (target != null) {
            try {
                Path fallback = fallbackShortcut(name, target, appDir);
                if (fallback != null) {
                    out.put("shortcut", fallback.toString());
                    out.put("shortcut_kind", "copied");
                }
            } catch (Exception ex) {
                out.put("shortcut_error", ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage());
            }
        }
        if (target != null && out.get("artifact") == null) {
            out.put("artifact", target.toString());
        }
    }

    private static final class ShortcutTarget {
        final Path target;
        final String arguments;
        final boolean icon;

        ShortcutTarget(Path target, String arguments, boolean icon) {
            this.target = target;
            this.arguments = arguments;
            this.icon = icon;
        }
    }

    private Path createShortcut(String name, Path target, String args,
                                Path appDir, boolean icon) throws IOException {
        if (target == null || !Files.exists(target)) {
            return null;
        }
        Path desktop = FileService.desktopDir();
        Files.createDirectories(desktop);
        if (WINDOWS) {
            Path lnk = desktop.resolve(name + ".lnk");
            Path script = workspace.resolve("logs").resolve(
                    "build-shortcut-" + UUID.randomUUID().toString().substring(0, 8) + ".ps1");
            try {
                Files.createDirectories(script.getParent());
                String ps = "$ErrorActionPreference = 'Stop'\n"
                        + "$ws = New-Object -ComObject WScript.Shell\n"
                        + "$s = $ws.CreateShortcut('" + psQuote(lnk.toString()) + "')\n"
                        + "$s.TargetPath = '" + psQuote(target.toString()) + "'\n"
                        + "$s.Arguments = '" + psQuote(args == null ? "" : args) + "'\n"
                        + "$s.WorkingDirectory = '" + psQuote(appDir.toString()) + "'\n"
                        + (icon ? "$s.IconLocation = '" + psQuote(target.toString()) + ",0'\n" : "")
                        + "$s.Description = '" + psQuote(name) + "'\n"
                        + "$s.Save()\n";
                byte[] body = ps.getBytes(StandardCharsets.UTF_8);
                byte[] withBom = new byte[body.length + 3];
                withBom[0] = (byte) 0xEF;
                withBom[1] = (byte) 0xBB;
                withBom[2] = (byte) 0xBF;
                System.arraycopy(body, 0, withBom, 3, body.length);
                Files.write(script, withBom);
                Exec run = exec(List.of("powershell.exe", "-NoLogo", "-NoProfile",
                        "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString()), Map.of(), appDir, 60);
                if (run.exitCode == 0 && Files.isRegularFile(lnk)) {
                    return lnk;
                }
            } finally {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                }
            }
            return null;
        }
        if (MAC) {
            Path command = desktop.resolve(name + ".command");
            String content = "#!/bin/zsh\ncd " + shQuote(appDir.toString()) + " || exit 1\n"
                    + "exec " + shQuote(target.toString())
                    + (args == null || args.isBlank() ? "" : " " + args) + "\n";
            Files.writeString(command, content, StandardCharsets.UTF_8);
            setExecutable(command);
            return command;
        }
        Path entry = desktop.resolve(name + ".desktop");
        String content = "[Desktop Entry]\nType=Application\nName=" + name
                + "\nExec=" + desktopExec(target, args)
                + "\nPath=" + appDir + "\nTerminal=true\n"
                + "Categories=Development;\n";
        Files.writeString(entry, content, StandardCharsets.UTF_8);
        setExecutable(entry);
        return entry;
    }

    private Path fallbackShortcut(String name, Path target, Path appDir) throws IOException {
        Path desktop = FileService.desktopDir();
        String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            Path copy = desktop.resolve(name + ".cmd");
            String content = "@echo off\r\nchcp 65001 >nul\r\ncd /d \""
                    + cmdQuote(appDir.toString()) + "\"\r\ncall \""
                    + cmdQuote(target.toString()) + "\"\r\n";
            Files.writeString(copy, content, StandardCharsets.UTF_8);
            return copy;
        }
        if (lower.endsWith(".exe")) {
            Path copy = desktop.resolve(name + ".exe");
            copy(target, copy);
            return copy;
        }
        if (lower.endsWith(".html")) {
            Path url = desktop.resolve(name + ".url");
            write(url, "[InternetShortcut]\nURL=" + target.toUri() + "\n");
            return url;
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* runtime and process helpers                                        */
    /* ------------------------------------------------------------------ */

    private ToolchainManager.Resolved require(String runtimeId, String message)
            throws BuildFailure {
        ToolchainManager.Resolved runtime = toolchains.resolve(runtimeId);
        if (runtime != null && runtime.available()) {
            return runtime;
        }
        if (Boolean.TRUE.equals(installConsent.get())) {
            Map<String, Object> installed = toolchains.ensureBlocking(runtimeId, true, 1800, null);
            toolchains.refreshDetection();
            runtime = toolchains.resolve(runtimeId);
            if (runtime != null && runtime.available()) {
                return runtime;
            }
            String error = Json.str(installed, "error", "");
            throw new BuildFailure(message + (error.isBlank()
                    ? "" : " (auto-install failed: " + error + ")"), null);
        }
        throw new BuildFailure(message + " Enable auto-install in Settings to let CodeForge "
                + "install it with your permission.", null);
    }

    private Path findCompiler(ToolchainManager.Resolved runtime, boolean cpp) throws BuildFailure {
        Path tool = sibling(runtime, cpp
                ? new String[]{"g++" + exeSuffix(), "clang++" + exeSuffix()}
                : new String[]{"gcc" + exeSuffix(), "clang" + exeSuffix()});
        if (tool == null) {
            tool = which(cpp ? "g++" : "gcc", runtime.environment);
        }
        if (tool == null && cpp) {
            tool = which("clang++", runtime.environment);
        }
        if (tool == null && !cpp) {
            tool = which("clang", runtime.environment);
        }
        if (tool == null) {
            throw new BuildFailure((cpp ? "C++" : "C") + " compiler not found", null);
        }
        return tool;
    }

    private Path sibling(ToolchainManager.Resolved runtime, String... names) {
        if (runtime == null || runtime.binDir == null) {
            return null;
        }
        for (String name : names) {
            Path candidate = runtime.binDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Path which(String name, Map<String, String> env) {
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String piece : path.split(Pattern.quote(File.pathSeparator))) {
            if (piece.isBlank()) {
                continue;
            }
            Path dir;
            try {
                dir = Path.of(piece);
            } catch (Exception ex) {
                continue;
            }
            for (String candidateName : new String[]{name, name + exeSuffix(),
                    name + ".cmd", name + ".bat"}) {
                Path candidate = dir.resolve(candidateName);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Path pythonForGui(ToolchainManager.Resolved runtime) {
        if (runtime.binDir != null) {
            Path pythonw = runtime.binDir.resolve("pythonw.exe");
            if (Files.isRegularFile(pythonw)) {
                return pythonw;
            }
        }
        return runtime.executable;
    }

    private Path findExecutable(Path dir, String name) throws IOException {
        Path exact = dir.resolve(name + (WINDOWS ? ".exe" : ""));
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lower.endsWith(".exe") || (!WINDOWS && Files.isExecutable(path));
                    })
                    .findFirst().orElse(null);
        }
    }

    private Path findFile(Path dir, String name) throws IOException {
        Path exact = dir.resolve(name);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        try (Stream<Path> files = Files.walk(dir, 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
    }

    private static final class Exec {
        boolean launched;
        boolean timedOut;
        int exitCode = -1;
        String out = "";
        String err = "";
        String command = "";
        String error = "";
        long durationMs;
    }

    private Exec exec(List<String> command, Map<String, String> extraEnv,
                      Path workdir, int timeoutSeconds) {
        Exec result = new Exec();
        result.command = String.join(" ", command);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workdir.toFile());
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        if (WINDOWS) {
            env.put("PATH", path);
            env.put("Path", path);
        }
        builder.environment().putAll(env);
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            result.error = ex.getMessage();
            return result;
        }
        result.launched = true;
        long started = System.currentTimeMillis();
        Capture stdout = new Capture(process.getInputStream(), 128000);
        Capture stderr = new Capture(process.getErrorStream(), 128000);
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

    private static final class Capture extends Thread {
        private final InputStream input;
        private final int limitBytes;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private String text = "";

        Capture(InputStream input, int limitBytes) {
            this.input = input;
            this.limitBytes = limitBytes;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    synchronized (kept) {
                        int room = limitBytes - kept.size();
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

    private static final class BuildFailure extends Exception {
        final Exec exec;

        BuildFailure(String message, Exec exec) {
            super(message);
            this.exec = exec;
        }
    }

    private static void putExec(Map<String, Object> out, Exec exec) {
        out.put("command", exec.command);
        out.put("exit_code", exec.exitCode);
        out.put("timed_out", exec.timedOut);
        out.put("stdout", tail(exec.out, 12000));
        out.put("stderr", tail(exec.err, 12000));
        if (!exec.error.isBlank()) {
            out.put("launch_error", exec.error);
        }
    }

    /* ------------------------------------------------------------------ */
    /* files and text                                                     */
    /* ------------------------------------------------------------------ */

    private Path resolveSource(String raw) throws BuildFailure {
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (Exception ex) {
            throw new BuildFailure("Invalid source path: " + raw, null);
        }
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        path = path.normalize().toAbsolutePath();
        if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
            throw new BuildFailure("Source not found: " + path, null);
        }
        return path;
    }

    private Path resolveDestination(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank() || value.equalsIgnoreCase("desktop") || value.equals("桌面")) {
            return FileService.desktopDir().toAbsolutePath();
        }
        if (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", "") + value.substring(1);
        }
        if (value.matches("(?i)^(?:desktop|桌面)[\\\\/].+")) {
            int slash = Math.max(value.indexOf('/'), value.indexOf('\\'));
            return FileService.desktopDir().resolve(value.substring(slash + 1))
                    .normalize().toAbsolutePath();
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (Exception ex) {
            return FileService.desktopDir();
        }
        if (!path.isAbsolute()) {
            path = FileService.desktopDir().resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    private void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            return;
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            return;
        }
        Files.createDirectories(target);
        long total = 0;
        int count = 0;
        try (Stream<Path> walk = Files.walk(source)) {
            List<Path> paths = walk.toList();
            for (Path path : paths) {
                if (path.equals(source)) {
                    continue;
                }
                Path relative = source.relativize(path);
                String first = relative.getNameCount() > 0
                        ? relative.getName(0).toString().toLowerCase(Locale.ROOT) : "";
                if (first.equals("node_modules") || first.equals(".git")
                        || first.equals("__pycache__") || first.equals(".venv")) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                    continue;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                long size = Files.size(path);
                if (total + size > MAX_COPY_BYTES || ++count > MAX_COPY_FILES) {
                    throw new IOException("The web folder is too large to copy (limit "
                            + (MAX_COPY_BYTES / (1024 * 1024)) + " MB / "
                            + MAX_COPY_FILES + " files)");
                }
                total += size;
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private Path writeCmd(Path appDir, String fileName, String command, boolean pause)
            throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("@echo off\r\n");
        content.append("chcp 65001 >nul\r\n");
        content.append("cd /d \"%~dp0\"\r\n");
        content.append(command).append("\r\n");
        if (pause) {
            content.append("if errorlevel 1 pause\r\n");
        }
        Path launcher = appDir.resolve(fileName);
        // cmd.exe re-reads the rest of the script after the chcp line, so UTF-8
        // content is required here; a GBK script garbles non-ASCII paths.
        Files.writeString(launcher, content.toString(), StandardCharsets.UTF_8);
        return launcher;
    }

    private static void setExecutable(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* language text helpers                                              */
    /* ------------------------------------------------------------------ */

    private static String javaTypeName(String code) {
        Matcher match = JAVA_PUBLIC_TYPE.matcher(code);
        if (match.find()) {
            return match.group(1);
        }
        match = JAVA_ANY_TYPE.matcher(code);
        return match.find() ? match.group(1) : "";
    }

    private static String javaPackage(String code) {
        Matcher match = JAVA_PACKAGE.matcher(code);
        return match.find() ? match.group(1) : "";
    }

    private static int[] parseVersion(String text) {
        Matcher match = Pattern.compile("(\\d+)\\.(\\d+)").matcher(text == null ? "" : text);
        if (match.find()) {
            return new int[]{Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))};
        }
        return new int[]{0, 0};
    }

    private static String extension(LanguageSupport.Language language) {
        if (language.extension != null && !language.extension.isBlank()) {
            return language.extension;
        }
        return ".txt";
    }

    private static String javaFlags(boolean gui) {
        return "-Dfile.encoding=UTF-8 ";
    }

    private static String exeSuffix() {
        return WINDOWS ? ".exe" : "";
    }

    private static String arch() {
        String value = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return value.contains("aarch64") || value.contains("arm64") ? "arm64" : "x64";
    }

    private static String cleanAppName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".cpp", ".cc", ".cxx", ".c", ".py", ".java",
                ".go", ".rs", ".cs", ".php", ".js", ".mjs", ".ts", ".html", ".css"}) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return name.substring(0, name.length() - ext.length()).trim();
            }
        }
        return name;
    }

    private static String safeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "").trim();
        while (value.endsWith(".") || value.endsWith(" ")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() > 60) {
            value = value.substring(0, 60).trim();
        }
        return value;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String javaIdentifier(String raw) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < raw.length(); ++index) {
            char ch = raw.charAt(index);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                value.append(ch);
            }
        }
        if (value.length() == 0) {
            return "";
        }
        if (!Character.isLetter(value.charAt(0)) && value.charAt(0) != '_'
                && value.charAt(0) != '$') {
            value.insert(0, '_');
        }
        return value.toString();
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

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String cmdQuote(String value) {
        return value.replace("%", "%%");
    }

    private static String psQuote(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String desktopExec(Path target, String args) {
        String value = target.toString();
        if (value.contains(" ")) {
            value = "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return args == null || args.isBlank() ? value : value + " " + args;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}