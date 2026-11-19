package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Detects and runs the native test suite of a project. It reuses the command
 * agent so missing runtimes are provisioned, publishing stays blocked and every
 * run is logged. No test suite is reported as "passed": absence of tests has its
 * own status so the model cannot claim unrun verification.
 */
public final class TestRunner {
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "out", "bin",
            "obj", ".venv", "venv", "env", "__pycache__", ".pytest_cache",
            ".gradle", ".idea", ".vscode", "vendor", "dist", "coverage",
            ".next", ".nuxt", ".cache", "cmake-build-debug", "cmake-build-release");

    private static final Pattern NO_TESTS = Pattern.compile(
            "(?i)(no tests? (?:were |was )?(?:found|ran|run|to run|collected)"
                    + "|collected 0 items|0 tests? (?:run|passed|found|executed)"
                    + "|no test files found|nothing to test|\\bNO-SOURCE\\b"
                    + "|tests run: 0[,.]|cannot find any tests)");

    private record Detection(String framework, String command, String reason) {
        boolean found() {
            return command != null && !command.isBlank();
        }
    }

    private final Path root;
    private final AgentShell shell;
    private final Set<String> prepared = ConcurrentHashMap.newKeySet();

    public TestRunner(Path root, AgentShell shell) {
        this.root = root;
        this.shell = shell;
    }

    public Map<String, Object> detect(String pathText) {
        long started = System.currentTimeMillis();
        Path cwd = resolveProject(pathText);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("kind", "test");
        out.put("cwd", cwd.toString());
        if (!Files.isDirectory(cwd)) {
            out.put("error", "Project directory does not exist: " + cwd);
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }
        Detection detection = detectAt(cwd);
        out.put("ok", true);
        out.put("detected", detection.found());
        out.put("framework", detection.framework());
        out.put("command", detection.command());
        out.put("reason", detection.reason());
        out.put("message", detection.found()
                ? "Detected " + detection.framework() + ": " + detection.command()
                : "No test suite detected in " + cwd);
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    public Map<String, Object> run(Map<String, Object> request, AtomicBoolean cancel,
                                   boolean autoInstall, int installTimeoutSeconds,
                                   ToolProvisioner.Listener listener) {
        long started = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("success", false);
        out.put("kind", "test");
        out.put("ran", false);
        out.put("command_ran", false);
        String pathText = firstNonBlank(Json.str(request, "path", ""),
                Json.str(request, "cwd", ""));
        Path cwd = resolveProject(pathText);
        out.put("cwd", cwd.toString());
        if (!Files.isDirectory(cwd)) {
            out.put("status", "error");
            out.put("error", "Project directory does not exist: " + cwd);
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }

        String explicit = Json.str(request, "command", "").trim();
        Detection detection = explicit.isBlank()
                ? detectAt(cwd) : new Detection("custom", explicit, "explicit test command");
        out.put("framework", detection.framework());
        out.put("reason", detection.reason());
        if (!detection.found()) {
            out.put("ok", true);
            out.put("status", "no-tests");
            out.put("detected", false);
            out.put("message", "No test suite detected in " + cwd
                    + ". Tests were not run; do not claim that they passed.");
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }
        out.put("detected", true);
        out.put("command", detection.command());

        Map<String, Object> preparation = prepare(detection, cwd, cancel, autoInstall,
                installTimeoutSeconds, listener);
        if (!preparation.isEmpty()) {
            out.put("prepare", preparation);
            String prepareError = Json.str(preparation, "error", "");
            if (!prepareError.isBlank()) {
                out.put("prepare_error", prepareError);
            }
        }

        int timeout = Math.max(30, Math.min(7200, Json.integer(request, "timeout", 900)));
        int installTimeout = Math.max(60, Math.min(86400, installTimeoutSeconds));
        String shellName = Json.str(request, "shell", "");
        Map<String, Object> result = shell.run(detection.command(), cwd.toString(),
                shellName, timeout, cancel, autoInstall, installTimeout, listener, false);
        boolean cancelled = Json.bool(result, "cancelled", false);
        boolean timedOut = Json.bool(result, "timed_out", false);
        boolean blocked = Json.bool(result, "blocked", false);
        int exit = Json.integer(result, "exit_code", -1);
        String stdout = tail(Json.str(result, "stdout_tail", ""), 24000);
        String stderr = tail(Json.str(result, "stderr_tail", ""), 24000);
        String combined = stdout + "\n" + stderr;
        boolean noTests = !blocked && exit == 5 && combined.toLowerCase(Locale.ROOT)
                .contains("no tests ran");
        if (!noTests && exit == 0 && NO_TESTS.matcher(combined).find()) {
            noTests = true;
        }

        out.put("command_ran", true);
        out.put("command", Json.str(result, "command", detection.command()));
        out.put("shell", Json.str(result, "shell", shellName));
        out.put("exit_code", exit);
        out.put("timed_out", timedOut);
        out.put("cancelled", cancelled);
        out.put("stdout", stdout);
        out.put("stderr", stderr);
        out.put("stdout_log", Json.str(result, "stdout_log", ""));
        out.put("stderr_log", Json.str(result, "stderr_log", ""));
        if (!Json.str(result, "error", "").isBlank()) {
            out.put("error", Json.str(result, "error", ""));
        }
        if (!Json.str(result, "publish_hint", "").isBlank()) {
            out.put("publish_hint", Json.str(result, "publish_hint", ""));
        }
        if (blocked) {
            out.put("blocked", true);
            out.put("status", "error");
            out.put("message", "The test command was blocked by the publishing guard. "
                    + "Nothing ran.");
        } else if (cancelled) {
            out.put("status", "cancelled");
            out.put("message", "Test run cancelled");
        } else if (timedOut) {
            out.put("status", "failed");
            out.put("message", "Tests timed out after " + timeout + " seconds");
        } else if (noTests) {
            out.put("ok", true);
            out.put("status", "no-tests");
            out.put("ran", false);
            out.put("message", "The test command ran but no tests were discovered. "
                    + "Do not claim that tests passed.");
        } else if (exit == 0) {
            out.put("ok", true);
            out.put("success", true);
            out.put("status", "passed");
            out.put("ran", true);
            out.put("message", "Tests passed");
        } else {
            out.put("status", "failed");
            out.put("ran", true);
            out.put("message", "Tests failed with exit code " + exit);
        }
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    private Map<String, Object> prepare(Detection detection, Path cwd,
                                        AtomicBoolean cancel, boolean autoInstall,
                                        int installTimeoutSeconds,
                                        ToolProvisioner.Listener listener) {
        Map<String, Object> out = new LinkedHashMap<>();
        String framework = detection.framework();
        if (!autoInstall) {
            return out;
        }
        String key = cwd.toAbsolutePath().normalize() + "|" + framework;
        if (prepared.contains(key)) {
            return out;
        }
        int timeout = Math.max(120, Math.min(1800, installTimeoutSeconds));
        try {
            if (framework.startsWith("node-")
                    && !Files.isDirectory(cwd.resolve("node_modules"))
                    && nodeHasDependencies(cwd)) {
                String manager = nodeManager(cwd);
                String command = switch (manager) {
                    case "pnpm" -> "pnpm install";
                    case "yarn" -> "yarn install";
                    case "bun" -> "bun install";
                    default -> "npm install --no-audit --no-fund --loglevel=error";
                };
                Map<String, Object> install = shell.run(command, cwd.toString(), "",
                        timeout, cancel, true, installTimeoutSeconds, listener, false);
                out.put("dependencies", Json.map(
                        "command", command,
                        "ok", Json.bool(install, "ok", false),
                        "exit_code", Json.integer(install, "exit_code", -1)));
                if (!Json.bool(install, "ok", false)) {
                    out.put("error", "Dependency install failed: "
                            + firstNonBlank(Json.str(install, "error", ""),
                            tail(Json.str(install, "stderr_tail", ""), 1200)));
                    return out;
                }
            } else if (framework.startsWith("python")
                    && detection.framework().equals("python-venv")) {
                String python = venvPythonCommand(cwd);
                Map<String, Object> inside = shell.run(python + " -m pytest --version",
                        cwd.toString(), "", 90, cancel, false, 90, listener, false);
                if (!Json.bool(inside, "ok", false)) {
                    Map<String, Object> install = shell.run(
                            python + " -m pip install --disable-pip-version-check pytest",
                            cwd.toString(), "", timeout, cancel, false, timeout,
                            listener, false);
                    out.put("pytest", Json.map(
                            "command", python + " -m pip install pytest",
                            "ok", Json.bool(install, "ok", false),
                            "exit_code", Json.integer(install, "exit_code", -1)));
                    if (!Json.bool(install, "ok", false)) {
                        out.put("error", "pytest is not installed in the project venv and "
                                + "pip install failed: "
                                + firstNonBlank(Json.str(install, "error", ""),
                                tail(Json.str(install, "stderr_tail", ""), 1200)));
                        return out;
                    }
                }
            } else if (framework.startsWith("php")
                    && !Files.isDirectory(cwd.resolve("vendor"))
                    && Files.isRegularFile(cwd.resolve("composer.json"))
                    && shell.provisioner().findCommand("composer") != null) {
                Map<String, Object> install = shell.run(
                        "composer install --no-interaction --prefer-dist --no-progress",
                        cwd.toString(), "", timeout, cancel, true, timeout,
                        listener, false);
                out.put("composer", Json.map(
                        "ok", Json.bool(install, "ok", false),
                        "exit_code", Json.integer(install, "exit_code", -1)));
                if (!Json.bool(install, "ok", false)) {
                    out.put("error", "composer install failed: "
                            + tail(Json.str(install, "stderr_tail", ""), 1200));
                    return out;
                }
            }
            prepared.add(key);
        } catch (Exception ex) {
            out.put("error", ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return out;
    }

    private Detection detectAt(Path cwd) {
        Path pkg = cwd.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            try {
                Map<String, Object> document = Json.asObject(Json.parse(
                        Files.readString(pkg, StandardCharsets.UTF_8)));
                Map<String, Object> scripts = Json.asObject(document.get("scripts"));
                String script = firstRunnableTestScript(scripts);
                if (!script.isBlank()) {
                    String manager = nodeManager(cwd);
                    return new Detection("node-" + manager,
                            manager + " run " + script,
                            "package.json script \"" + script + "\"");
                }
                if (hasAny(cwd, "vitest.config.js", "vitest.config.ts",
                        "vitest.config.mjs", "vitest.config.mts")) {
                    return new Detection("node-vitest",
                            "npx --no-install vitest run",
                            "vitest configuration");
                }
                if (hasAny(cwd, "jest.config.js", "jest.config.ts",
                        "jest.config.cjs", "jest.config.mjs")) {
                    return new Detection("node-jest",
                            "npx --no-install jest --ci",
                            "jest configuration");
                }
                if (hasAny(cwd, "playwright.config.js", "playwright.config.ts")) {
                    return new Detection("node-playwright",
                            "npx --no-install playwright test",
                            "playwright configuration");
                }
            } catch (Exception ignored) {
            }
        }

        boolean pytestConfig = hasAny(cwd, "pytest.ini", "conftest.py", "tox.ini")
                || containsAny(cwd.resolve("pyproject.toml"), "[tool.pytest")
                || containsAny(cwd.resolve("setup.cfg"), "[tool:pytest]")
                || containsAny(cwd.resolve("tox.ini"), "[pytest]");
        boolean pythonTests = findAny(cwd, 3, path -> isPythonTest(path), false) != null;
        if (pytestConfig || pythonTests) {
            String python = venvPythonCommand(cwd);
            if (python != null) {
                return new Detection("python-venv", python + " -m pytest -q",
                        pythonTests ? "Python test files and project venv"
                                : "pytest configuration and project venv");
            }
            return new Detection("python-pytest", "pytest -q",
                    pythonTests ? "Python test files" : "pytest configuration");
        }

        boolean maven = Files.isRegularFile(cwd.resolve("pom.xml"));
        if (maven && hasJavaLikeTests(cwd)) {
            boolean wrapper = Files.isRegularFile(cwd.resolve(
                    isWindows() ? "mvnw.cmd" : "mvnw"));
            String command = wrapper
                    ? (isWindows() ? ".\\mvnw.cmd -B test" : "./mvnw -B test")
                    : "mvn -B test";
            return new Detection("java-maven", command,
                    "Maven project with test sources");
        }

        boolean gradle = Files.isRegularFile(cwd.resolve("build.gradle"))
                || Files.isRegularFile(cwd.resolve("build.gradle.kts"));
        if (gradle && hasJavaLikeTests(cwd)) {
            boolean wrapper = Files.isRegularFile(cwd.resolve(
                    isWindows() ? "gradlew.bat" : "gradlew"));
            String command = wrapper
                    ? (isWindows() ? ".\\gradlew.bat test --console=plain"
                    : "./gradlew test --console=plain")
                    : "gradle test --console=plain";
            return new Detection("java-gradle", command,
                    "Gradle project with test sources");
        }

        if (Files.isRegularFile(cwd.resolve("Cargo.toml"))) {
            boolean tests = Files.isDirectory(cwd.resolve("tests"))
                    || findAny(cwd.resolve("src"), 8,
                    path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".rs")
                            && containsAny(path, "#[test]", "#[tokio::test]",
                            "#[async_std::test]"), false) != null;
            if (tests) {
                return new Detection("rust-cargo", "cargo test",
                        "Cargo tests");
            }
        }

        if (Files.isRegularFile(cwd.resolve("go.mod"))) {
            boolean tests = findAny(cwd, 12, path -> path.getFileName().toString()
                    .endsWith("_test.go"), false) != null;
            if (tests) {
                return new Detection("go-test", "go test ./...",
                        "Go *_test.go files");
            }
        }

        Path dotnetTest = findAny(cwd, 5, path -> {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".csproj") && !name.endsWith(".fsproj")
                    && !name.endsWith(".vbproj")) {
                return false;
            }
            return name.contains("test")
                    || containsAny(path, "microsoft.net.test.sdk",
                    "<istestproject>true</istestproject>");
        }, false);
        if (dotnetTest != null) {
            return new Detection("dotnet-test",
                    "dotnet test \"" + dotnetTest.toString() + "\" --nologo "
                            + "--verbosity minimal", ".NET test project");
        }

        boolean phpunitConfig = hasAny(cwd, "phpunit.xml", "phpunit.xml.dist");
        boolean phpTests = phpunitConfig || findAny(cwd, 6,
                path -> path.getFileName().toString().endsWith("Test.php"), false) != null;
        if (phpTests) {
            Map<String, Object> composer = readObject(cwd.resolve("composer.json"));
            Map<String, Object> scripts = Json.asObject(composer.get("scripts"));
            String script = firstRunnableTestScript(scripts);
            if (!script.isBlank()) {
                return new Detection("php-composer",
                        "composer run " + script,
                        "composer script \"" + script + "\"");
            }
            return new Detection("php-phpunit",
                    "php vendor/phpunit/phpunit/phpunit",
                    "PHPUnit test files");
        }

        if (Files.isRegularFile(cwd.resolve("CMakeLists.txt"))) {
            Path ctest = findCtestDirectory(cwd);
            if (ctest != null) {
                return new Detection("cmake-ctest",
                        "ctest --test-dir \"" + ctest.toString()
                                + "\" --output-on-failure",
                        "CMake test build");
            }
        }
        if (Files.isRegularFile(cwd.resolve("Makefile"))
                && containsAny(cwd.resolve("Makefile"), "\ntest:", "\ntest :",
                "\ncheck:")) {
            return new Detection("make-test", "make test", "Makefile test target");
        }

        if (Files.isRegularFile(cwd.resolve("Gemfile"))
                && Files.isDirectory(cwd.resolve("spec"))) {
            return new Detection("ruby-rspec", "bundle exec rspec",
                    "RSpec test suite");
        }
        if (Files.isRegularFile(cwd.resolve("Rakefile"))
                && containsAny(cwd.resolve("Rakefile"), "test")) {
            return new Detection("ruby-rake", "rake test", "Rake test task");
        }
        return new Detection("", "", "no supported test layout detected");
    }

    private Path resolveProject(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank() || value.equals(".")) {
            return root.normalize().toAbsolutePath();
        }
        if (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", "") + value.substring(1);
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (Exception ex) {
            return root.resolve("__invalid_project_path__");
        }
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        path = path.normalize().toAbsolutePath();
        return Files.isRegularFile(path) && path.getParent() != null
                ? path.getParent() : path;
    }

    private static String firstRunnableTestScript(Map<String, Object> scripts) {
        if (scripts == null || scripts.isEmpty()) {
            return "";
        }
        String direct = Json.str(scripts, "test", "").trim();
        if (runnableScript(direct)) {
            return "test";
        }
        for (String key : scripts.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if ((lower.equals("test") || lower.startsWith("test:") || lower.startsWith("test-"))
                    && runnableScript(Json.str(scripts, key, ""))) {
                return key;
            }
        }
        return "";
    }

    private static boolean runnableScript(String script) {
        String lower = script == null ? "" : script.toLowerCase(Locale.ROOT);
        return !lower.isBlank() && !lower.contains("no test specified")
                && !lower.contains("no tests specified")
                && !lower.contains("error: no test");
    }

    private static String nodeManager(Path cwd) {
        if (hasAny(cwd, "pnpm-lock.yaml")) {
            return "pnpm";
        }
        if (hasAny(cwd, "yarn.lock")) {
            return "yarn";
        }
        if (hasAny(cwd, "bun.lockb", "bun.lock")) {
            return "bun";
        }
        return "npm";
    }

    private static boolean nodeHasDependencies(Path cwd) {
        if (hasAny(cwd, "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
                "bun.lockb", "bun.lock")) {
            return true;
        }
        Map<String, Object> document = readObject(cwd.resolve("package.json"));
        return !Json.asObject(document.get("dependencies")).isEmpty()
                || !Json.asObject(document.get("devDependencies")).isEmpty();
    }

    private static boolean hasJavaLikeTests(Path cwd) {
        return findAny(cwd, 10, path -> {
            String value = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return value.contains("/src/test/");
        }, false) != null;
    }

    private static boolean isPythonTest(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("test_") && name.endsWith(".py")
                || name.endsWith("_test.py");
    }

    private static Path findCtestDirectory(Path cwd) {
        for (String name : List.of("build", "cmake-build-debug", "cmake-build-release",
                "out/build", "cmake-build-relwithdebinfo")) {
            Path candidate = cwd.resolve(name);
            if (Files.isRegularFile(candidate.resolve("CTestTestfile.cmake"))) {
                return candidate;
            }
        }
        Path nested = findAny(cwd, 3, path -> path.getFileName().toString()
                .equals("CTestTestfile.cmake"), false);
        return nested == null ? null : nested.getParent();
    }

    private static String venvPythonCommand(Path cwd) {
        Path[] candidates = isWindows()
                ? new Path[]{cwd.resolve(".venv").resolve("Scripts").resolve("python.exe"),
                cwd.resolve("venv").resolve("Scripts").resolve("python.exe"),
                cwd.resolve("env").resolve("Scripts").resolve("python.exe")}
                : new Path[]{cwd.resolve(".venv").resolve("bin").resolve("python"),
                cwd.resolve("venv").resolve("bin").resolve("python"),
                cwd.resolve("env").resolve("bin").resolve("python")};
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                Path relative = cwd.relativize(candidate);
                String text = relative.toString();
                return isWindows() ? ".\\" + text : "./" + text.replace('\\', '/');
            }
        }
        return null;
    }

    private static Path findAny(Path start, int maxDepth, Predicate<Path> match,
                                boolean includeSkipped) {
        if (start == null || !Files.isDirectory(start)) {
            return null;
        }
        final Path[] found = new Path[1];
        try {
            Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir,
                                                                 BasicFileAttributes attrs) {
                            if (!includeSkipped && !dir.equals(start)
                                    && SKIP_DIRS.contains(dir.getFileName().toString()
                                    .toLowerCase(Locale.ROOT))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file,
                                                         BasicFileAttributes attrs) {
                            if (match.test(file)) {
                                found[0] = file;
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Exception ignored) {
        }
        return found[0];
    }

    private static boolean hasAny(Path dir, String... names) {
        for (String name : names) {
            if (Files.exists(dir.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(Path file, String... needles) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static Map<String, Object> readObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.asObject(Json.parse(Files.readString(file,
                    StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim()
                : (second == null ? "" : second.trim());
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : "…" + text.substring(text.length() - max);
    }
}