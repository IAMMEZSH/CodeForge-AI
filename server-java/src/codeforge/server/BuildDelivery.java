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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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

    private final Path root;
    private final Path workspace;
    private final ToolchainManager toolchains;

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
            String name = safeName(Json.str(request, "name", ""));
            if (name.isBlank()) {
                name = safeName(source != null ? stripExtension(source.getFileName().toString())
                        : "CodeForgeApp");
            }
            if (name.isBlank()) {
                name = "CodeForgeApp";
            }
            boolean gui = Json.bool(request, "gui", false);
            Path destination = resolveDestination(Json.str(request, "destination", ""));
            if (Files.exists(destination) && !Files.isDirectory(destination)) {
                throw new BuildFailure("The destination is a file, not a folder: " + destination, null);
            }
            Files.createDirectories(destination);
            appDir = destination.resolve(name);
            if (Files.exists(appDir) && !Files.isDirectory(appDir)) {
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
                    buildPython(source, name, gui, appDir, out);
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
            out.put("phase", "build");
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
            out.put("phase", "build");
            out.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            if (appDir != null) {
                out.put("folder", appDir.toString());
            }
        }
        out.put("duration_ms", System.currentTimeMillis() - started);
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
        finish(out, name, launcher, "", appDir, null);
    }

    private void buildGo(Path source, String name, Path appDir,
                         Map<String, Object> out) throws Exception {
        ToolchainManager.Resolved runtime = require("go",
                "Go is not installed. Install Go and retry.");
        String exeName = name + (WINDOWS ? ".exe" : "");
        Path localSource = appDir.resolve("main.go");
        copy(source, localSource);
        Path exe = appDir.resolve(exeName);
        Exec build = exec(List.of(runtime.executable.toString(), "build", "-trimpath",
                "-o", exeName, "main.go"), runtime.environment, appDir, 240);
        if (!build.launched || build.timedOut || build.exitCode != 0 || !Files.isRegularFile(exe)) {
            throw new BuildFailure("Go compilation failed", build);
        }
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("compiler", runtime.executable.toString());
        out.put("artifact", exe.toString());
        out.put("artifact_kind", "executable");
        out.put("stdout", tail(build.out, 8000));
        out.put("stderr", tail(build.err, 8000));
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
        finish(out, name, exe, "", appDir, null);
    }

    private void buildPython(Path source, String name, boolean gui,
                             Path appDir, Map<String, Object> out) throws Exception {
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
        Path python = runtime.executable;
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
        String command = "\"" + cmdQuote(runtime.executable.toString()) + "\"" + flag
                + " \"" + cmdQuote(script.getFileName().toString()) + "\"";
        Path launcher = writeCmd(appDir, name + ".cmd", command, true);
        out.put("runtime", runtime.name + " " + runtime.version);
        out.put("artifact", script.toString());
        out.put("artifact_kind", typescript ? "typescript" : "javascript");
        out.put("launcher", launcher.toString());
        finish(out, name, launcher, "", appDir, null);
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
        if (runtime == null || !runtime.available()) {
            throw new BuildFailure(message, null);
        }
        return runtime;
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