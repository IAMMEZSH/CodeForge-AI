package codeforge.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles and runs languages that the C++ core does not own, and builds
 * sandboxed HTML/CSS preview documents. The response shape mirrors
 * {@code /api/run} from the core so the console can render both identically.
 */
public final class ExtendedRunner {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern IMPORT_EXPORT = Pattern.compile(
            "(?m)^\\s*(?:import\\s|export\\s)");

    private final Path root;
    private final Path workspace;
    private final ToolchainManager toolchains;

    public ExtendedRunner(Path root, ToolchainManager toolchains) {
        this.root = root;
        this.workspace = root.resolve("workspace");
        this.toolchains = toolchains;
    }

    public boolean handles(LanguageSupport.Language language) {
        if (language == null) {
            return false;
        }
        if (language.core) {
            return "python".equals(language.id) && toolchains.managedInUse("python");
        }
        return true;
    }

    public Map<String, Object> run(Map<String, Object> request) {
        LanguageSupport.Language language = LanguageSupport.normalize(
                Json.str(request, "language", "auto"), Json.str(request, "filename", ""));
        if (language == null) {
            return failure("setup", "Unsupported language. Use Python, C, C++, Java, JavaScript, "
                    + "TypeScript, Go, Rust, PHP, C#, HTML or CSS");
        }
        long started = System.currentTimeMillis();
        Path session = workspace.resolve("run-" + LocalDateTime.now().format(STAMP)
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(session);
        } catch (IOException ex) {
            return failure("setup", "Cannot create the working directory: " + ex.getMessage());
        }
        Map<String, Object> base = base(language, session);
        try {
            switch (language.id) {
                case "python":
                    return runPython(request, language, session, base, started);
                case "javascript":
                case "typescript":
                    return runNode(request, language, session, base, started);
                case "go":
                    return runGo(request, language, session, base, started);
                case "rust":
                    return runRust(request, language, session, base, started);
                case "php":
                    return runPhp(request, language, session, base, started);
                case "csharp":
                    return runCsharp(request, language, session, base, started);
                case "html":
                case "css":
                    return runPreview(request, language, session, base, started);
                default:
                    return failure("setup", "This language is executed by the C++ core");
            }
        } catch (Exception ex) {
            base.put("ok", false);
            base.put("success", false);
            base.put("phase", "setup");
            base.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            base.put("duration_ms", System.currentTimeMillis() - started);
            return base;
        }
    }

    /* ------------------------------------------------------------------ */
    /* individual runtimes                                                */
    /* ------------------------------------------------------------------ */

    private Map<String, Object> runPython(Map<String, Object> request,
                                          LanguageSupport.Language language, Path session,
                                          Map<String, Object> base, long started) throws Exception {
        ToolchainManager.Resolved runtime = toolchains.resolve("python");
        if (runtime == null || !runtime.available()) {
            return missingToolchain(base, "python",
                    "Python interpreter not found. Install it from the System page.");
        }
        String sourceName = sourceName(request, language, "main.py");
        Path source = session.resolve(sourceName);
        write(source, Json.str(request, "code", ""));
        List<String> command = List.of(runtime.executable.toString(), "-X", "utf8", "-u",
                source.toString());
        return execute(command, runtime, session, base, started, 40, "run", sourceName);
    }

    private Map<String, Object> runNode(Map<String, Object> request,
                                        LanguageSupport.Language language, Path session,
                                        Map<String, Object> base, long started) throws Exception {
        ToolchainManager.Resolved runtime = toolchains.resolve("node");
        if (runtime == null || !runtime.available()) {
            return missingToolchain(base, "node",
                    "Node.js is not installed. Install it from the System page.");
        }
        String code = Json.str(request, "code", "");
        boolean typescript = "typescript".equals(language.id);
        String sourceName = sourceName(request, language, typescript ? "main.ts" : "main.js");
        Path source = session.resolve(sourceName);
        write(source, code);
        if (IMPORT_EXPORT.matcher(code).find()) {
            write(session.resolve("package.json"), "{\"type\":\"module\"}\n");
        }
        List<String> command = new ArrayList<>();
        command.add(runtime.executable.toString());
        if (typescript) {
            int[] nodeVersion = parseNodeVersion(runtime.version);
            if (nodeVersion[0] > 23 || (nodeVersion[0] == 23 && nodeVersion[1] >= 6)) {
                // Type stripping is on by default.
            } else if (nodeVersion[0] == 22 && nodeVersion[1] >= 6) {
                command.add("--experimental-strip-types");
            } else {
                base.put("ok", false);
                base.put("success", false);
                base.put("phase", "setup");
                base.put("error", "Node " + runtime.version + " cannot run TypeScript directly. "
                        + "Install Node 22.6 or newer from the System page.");
                base.put("duration_ms", System.currentTimeMillis() - started);
                return base;
            }
        }
        command.add(source.toString());
        return execute(command, runtime, session, base, started, 60, "run", sourceName);
    }

    private Map<String, Object> runGo(Map<String, Object> request,
                                      LanguageSupport.Language language, Path session,
                                      Map<String, Object> base, long started) throws Exception {
        ToolchainManager.Resolved runtime = toolchains.resolve("go");
        if (runtime == null || !runtime.available()) {
            return missingToolchain(base, "go",
                    "Go is not installed. Install it from the System page.");
        }
        String code = Json.str(request, "code", "");
        if (!Pattern.compile("(?m)^\\s*package\\s+\\w+").matcher(code).find()) {
            code = "package main\n\n" + code;
        }
        String sourceName = sourceName(request, language, "main.go");
        Path source = session.resolve(sourceName);
        write(source, code);
        String exeName = isWindows() ? "program.exe" : "program";
        List<String> build = List.of(runtime.executable.toString(), "build", "-o",
                session.resolve(exeName).toString(), source.getFileName().toString());
        Exec compile = exec(build, runtime, session, 180);
        if (!compile.launched) {
            base.put("error", compile.error);
            base.put("duration_ms", System.currentTimeMillis() - started);
            return base;
        }
        if (compile.timedOut || compile.exitCode != 0) {
            return compileFailure(base, compile, started, "Go compilation failed");
        }
        List<String> run = List.of(session.resolve(exeName).toString());
        return execute(run, runtime, session, base, started, 40, "run", sourceName);
    }

    private Map<String, Object> runRust(Map<String, Object> request,
                                        LanguageSupport.Language language, Path session,
                                        Map<String, Object> base, long started) throws Exception {
        ToolchainManager.Resolved runtime = toolchains.resolve("rust");
        if (runtime == null || !runtime.available()) {
            return missingToolchain(base, "rust",
                    "Rust is not installed. Install it from the System page.");
        }
        String sourceName = sourceName(request, language, "main.rs");
        Path source = session.resolve(sourceName);
        write(source, Json.str(request, "code", ""));
        String exeName = isWindows() ? "program.exe" : "program";
        List<String> build = List.of(runtime.executable.toString(), "-O",
                source.getFileName().toString(), "-o", exeName);
        Exec compile = exec(build, runtime, session, 180);
        if (!compile.launched) {
            base.put("error", compile.error);
            base.put("duration_ms", System.currentTimeMillis() - started);
            return base;
        }
        if (compile.timedOut || compile.exitCode != 0) {
            return compileFailure(base, compile, started, "Rust compilation failed");
        }
        List<String> run = List.of(session.resolve(exeName).toString());
        return execute(run, runtime, session, base, started, 40, "run", sourceName);
    }

    private Map<String, Object> runPhp(Map<String, Object> request,
                                       LanguageSupport.Language language, Path session,
                                       Map<String, Object> base, long started) throws Exception {
        ToolchainManager.Resolved runtime = toolchains.resolve("php");
        if (runtime == null || !runtime.available()) {
            return missingToolchain(base, "php",
                    "PHP is not installed. Install it from the System page.");
        }
        String code = Json.str(request, "code", "");
        if (!code.stripLeading().startsWith("<?php")) {
            code = "<?php\n" + code;
        }
        String sourceName = sourceName(request, language, "main.php");
        Path source = session.resolve(sourceName);
        write(source, code);
        List<String> command = List.of(runtime.executable.toString(),
                "-d", "display_errors=1", "-d", "error_reporting=E_ALL",
                source.getFileName().toString());
        return execute(command, runtime, session, base, started, 40, "run", sourceName);
    }

    private Map<String, Object> runCsharp(Map<String, Object> request,
                                          LanguageSupport.Language language, Path session,
                                          Map<String, Object> base, long started) throws Exception {
        ToolchainManager.Resolved runtime = toolchains.resolve("dotnet");
        if (runtime == null || !runtime.available()) {
            return missingToolchain(base, "dotnet",
                    "The .NET SDK is not installed. Install it from the System page.");
        }
        String sourceName = sourceName(request, language, "Program.cs");
        Path source = session.resolve(sourceName);
        write(source, Json.str(request, "code", ""));
        String sdkMajor = runtime.version == null ? "" : runtime.version.trim().split("\\.")[0];
        String tfm = sdkMajor.matches("\\d+") ? "net" + sdkMajor + ".0" : "net8.0";
        write(session.resolve("CodeForge.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>Exe</OutputType>
                    <TargetFramework>%s</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                    <AssemblyName>Program</AssemblyName>
                    <RootNamespace>CodeForge</RootNamespace>
                  </PropertyGroup>
                </Project>
                """.formatted(tfm));
        List<String> command = List.of(runtime.executable.toString(), "run",
                "--project", session.resolve("CodeForge.csproj").toString(),
                "--nologo", "-v", "q");
        return execute(command, runtime, session, base, started, 300, "run", sourceName, true);
    }

    private Map<String, Object> runPreview(Map<String, Object> request,
                                           LanguageSupport.Language language, Path session,
                                           Map<String, Object> base, long started) throws Exception {
        boolean cssOnly = "css".equals(language.id);
        String code = Json.str(request, "code", "");
        String css = cssOnly ? code : Json.str(request, "css", "");
        String html = cssOnly ? Json.str(request, "html", "") : code;
        String page = buildHtmlPage(html, css, cssOnly);
        Path index = session.resolve("index.html");
        write(index, page);
        if (cssOnly) {
            write(session.resolve("style.css"), css);
        }
        base.put("ok", true);
        base.put("success", true);
        base.put("phase", "preview");
        base.put("language", "HTML/CSS");
        base.put("source_file", "index.html");
        base.put("command", "sandboxed browser preview");
        base.put("exit_code", 0);
        base.put("timed_out", false);
        base.put("duration_ms", System.currentTimeMillis() - started);
        base.put("stdout", "Preview ready in a sandboxed frame.");
        base.put("stderr", "");
        base.put("preview_url", "/preview/" + session.getFileName() + "/index.html");
        return base;
    }

    /* ------------------------------------------------------------------ */
    /* execution                                                          */
    /* ------------------------------------------------------------------ */

    private Map<String, Object> execute(List<String> command, ToolchainManager.Resolved runtime,
                                        Path session, Map<String, Object> base, long started,
                                        int timeoutSeconds, String phase, String sourceName)
            throws IOException {
        return execute(command, runtime, session, base, started, timeoutSeconds, phase,
                sourceName, false);
    }

    private Map<String, Object> execute(List<String> command, ToolchainManager.Resolved runtime,
                                        Path session, Map<String, Object> base, long started,
                                        int timeoutSeconds, String phase, String sourceName,
                                        boolean buildPhase) throws IOException {
        Exec result = exec(command, runtime, session, timeoutSeconds);
        base.put("source_file", sourceName);
        base.put("command", result.command);
        base.put("phase", phase);
        base.put("stdout", truncate(result.out, 64000));
        base.put("stderr", truncate(result.err, 64000));
        base.put("exit_code", result.timedOut ? 124 : result.exitCode);
        base.put("timed_out", result.timedOut);
        base.put("duration_ms", Math.max(result.durationMs, System.currentTimeMillis() - started));
        if (!result.launched) {
            base.put("ok", false);
            base.put("success", false);
            base.put("error", result.error);
            return base;
        }
        if (result.timedOut) {
            base.put("ok", false);
            base.put("success", false);
            base.put("phase", "run");
            base.put("error", "Program timed out after " + timeoutSeconds + " seconds");
            return base;
        }
        boolean ok = result.exitCode == 0;
        base.put("ok", ok);
        base.put("success", ok);
        if (!ok) {
            base.put("error", buildPhase ? "Build or run failed" : "Program exited with code "
                    + result.exitCode);
        }
        return base;
    }

    private Map<String, Object> compileFailure(Map<String, Object> base, Exec compile,
                                                long started, String message) {
        base.put("ok", false);
        base.put("success", false);
        base.put("phase", "compile");
        base.put("command", compile.command);
        base.put("stdout", truncate(compile.out, 64000));
        base.put("stderr", truncate(compile.err, 64000));
        base.put("exit_code", compile.timedOut ? 124 : compile.exitCode);
        base.put("timed_out", compile.timedOut);
        base.put("duration_ms", Math.max(compile.durationMs, System.currentTimeMillis() - started));
        base.put("error", compile.timedOut ? "Compilation timed out" : message);
        return base;
    }

    private static final class Exec {
        boolean launched;
        boolean timedOut;
        int exitCode = -1;
        String out = "";
        String err = "";
        long durationMs;
        String command = "";
        String error = "";
    }

    private Exec exec(List<String> command, ToolchainManager.Resolved runtime,
                      Path workdir, int timeoutSeconds) throws IOException {
        Exec result = new Exec();
        result.command = displayCommand(command);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workdir.toFile());
        if (runtime != null) {
            builder.environment().putAll(runtime.environment);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            result.error = ex.getMessage();
            return result;
        }
        result.launched = true;
        long started = System.currentTimeMillis();
        Capture stdout = new Capture(process.getInputStream(), 64000);
        Capture stderr = new Capture(process.getErrorStream(), 64000);
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

        Capture(InputStream input, int limitChars) {
            this.input = input;
            this.limitBytes = limitChars * 3;
            setDaemon(true);
            setName("run-output");
        }

        @Override
        public void run() {
            // Keep raw bytes and decode once: chunk boundaries can split multi-byte
            // characters, and GBK output from localized tools needs a fallback.
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

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    private Map<String, Object> base(LanguageSupport.Language language, Path session) {
        return Json.map(
                "ok", false,
                "success", false,
                "phase", "setup",
                "language", language.name,
                "source_file", "",
                "command", "",
                "exit_code", -1,
                "timed_out", false,
                "duration_ms", 0,
                "stdout", "",
                "stderr", "",
                "error", "",
                "session_dir", root.relativize(session).toString());
    }

    private Map<String, Object> failure(String phase, String error) {
        return Json.map(
                "ok", false, "success", false, "phase", phase,
                "language", "", "source_file", "", "command", "",
                "exit_code", -1, "timed_out", false, "duration_ms", 0,
                "stdout", "", "stderr", "", "error", error, "session_dir", "");
    }

    private Map<String, Object> missingToolchain(Map<String, Object> base,
                                                 String runtime, String message) {
        Map<String, Object> info = toolchains.ensure(runtime, true);
        base.put("ok", false);
        base.put("success", false);
        base.put("phase", "setup");
        base.put("error", message);
        base.put("toolchain", info);
        return base;
    }

    private String sourceName(Map<String, Object> request,
                              LanguageSupport.Language language, String fallback) {
        String requested = Json.str(request, "filename", "").trim();
        if (requested.isEmpty()) {
            return fallback;
        }
        String base = requested.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) {
            return fallback;
        }
        if (!base.toLowerCase(Locale.ROOT).endsWith(language.extension)) {
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            base += language.extension;
        }
        return base;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String displayCommand(List<String> command) {
        StringBuilder out = new StringBuilder();
        for (String part : command) {
            if (out.length() > 0) {
                out.append(' ');
            }
            if (part.contains(" ")) {
                out.append('"').append(part).append('"');
            } else {
                out.append(part);
            }
        }
        return out.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n[output truncated]";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static int[] parseNodeVersion(String version) {
        Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)").matcher(version == null ? "" : version);
        if (matcher.find()) {
            return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
        return new int[]{0, 0};
    }

    /* ------------------------------------------------------------------ */
    /* HTML / CSS preview                                                 */
    /* ------------------------------------------------------------------ */

    private static String buildHtmlPage(String html, String css, boolean cssOnly) {
        String style = css == null ? "" : css;
        String body = html == null ? "" : html;
        if (cssOnly) {
            if (body.isBlank()) {
                body = """
                        <header class="header">
                          <h1>CodeForge CSS Preview</h1>
                          <p>Generated stylesheet applied to demo markup.</p>
                        </header>
                        <main class="container">
                          <section class="card">
                            <h2>Card title</h2>
                            <p>This sample shows typography, spacing and color from your stylesheet.</p>
                            <button class="button">Action</button>
                          </section>
                          <ul class="list">
                            <li>First item</li>
                            <li>Second item</li>
                            <li>Third item</li>
                          </ul>
                        </main>
                        """;
            }
            return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                    + "<title>CodeForge CSS Preview</title>\n<style>\n" + style
                    + "\n</style>\n</head>\n<body>\n" + body + "\n</body>\n</html>\n";
        }
        String lower = body.toLowerCase(Locale.ROOT);
        boolean fullDocument = lower.contains("<html") || lower.contains("<!doctype");
        if (!fullDocument) {
            return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                    + "<title>CodeForge Preview</title>\n<style>\n" + style
                    + "\n</style>\n</head>\n<body>\n" + body + "\n</body>\n</html>\n";
        }
        String page = body;
        if (!lower.contains("charset")) {
            int head = lower.indexOf("<head");
            if (head >= 0) {
                int close = page.indexOf('>', head);
                if (close > 0) {
                    page = page.substring(0, close + 1) + "\n<meta charset=\"utf-8\">"
                            + page.substring(close + 1);
                }
            }
        }
        if (!style.isBlank()) {
            String styleTag = "\n<style>\n" + style + "\n</style>\n";
            Matcher head = Pattern.compile("(?i)</head>").matcher(page);
            if (head.find()) {
                page = page.substring(0, head.start()) + styleTag + page.substring(head.start());
            } else {
                page = styleTag + page;
            }
        }
        return page;
    }
}
