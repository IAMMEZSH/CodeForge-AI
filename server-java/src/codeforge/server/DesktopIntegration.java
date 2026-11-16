package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates a launcher on the desktop or at any path the user provides.
 * Windows uses a real .lnk through WScript.Shell; Linux writes a .desktop
 * entry; macOS writes an executable .command script.
 */
public final class DesktopIntegration {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final Path root;
    private final FileService files;

    public DesktopIntegration(Path root, FileService files) {
        this.root = root;
        this.files = files;
    }

    public Map<String, Object> install(Map<String, Object> request) {
        return installForProject(root, request);
    }

    public Map<String, Object> installForProject(Path projectRoot, Map<String, Object> request) {
        String raw = Json.str(request, "path", "").trim();
        boolean createDirs = Json.bool(request, "create_dirs", false);
        try {
            Path desktop = desktopDir();
            Path requested = raw.isEmpty() ? desktop : files.resolvePath(raw);
            if (Files.exists(requested) && Files.isRegularFile(requested)
                    && !launcherExtension(requested.getFileName().toString())) {
                return Json.map("ok", false,
                        "error", "A file already exists at that path: " + requested,
                        "path", requested.toString());
            }
            boolean directoryTarget = raw.isEmpty() || Files.isDirectory(requested)
                    || raw.endsWith("/") || raw.endsWith("\\")
                    || (!Files.exists(requested)
                            && !launcherExtension(requested.getFileName() == null
                                    ? "" : requested.getFileName().toString()));
            Path launcher;
            if (directoryTarget) {
                if (!Files.isDirectory(requested)) {
                    if (Files.exists(requested)) {
                        return Json.map("ok", false,
                                "error", "The destination is a file, not a folder: " + requested,
                                "path", requested.toString());
                    }
                    if (!createDirs) {
                        return Json.map("ok", false,
                                "error", "The folder does not exist: " + requested
                                        + ". Confirm creating it, then try again.",
                                "path", requested.toString(),
                                "missing_directory", requested.toString(),
                                "can_create", true);
                    }
                    Files.createDirectories(requested);
                }
                launcher = requested.resolve(launcherName());
            } else {
                Path parent = requested.getParent();
                if (parent == null) {
                    return Json.map("ok", false, "error", "The destination is not a file path");
                }
                if (!Files.isDirectory(parent)) {
                    if (Files.exists(parent)) {
                        return Json.map("ok", false,
                                "error", "The parent path is a file, not a folder: " + parent,
                                "path", parent.toString());
                    }
                    if (!createDirs) {
                        return Json.map("ok", false,
                                "error", "The folder does not exist: " + parent
                                        + ". Confirm creating it, then try again.",
                                "path", parent.toString(),
                                "missing_directory", parent.toString(),
                                "can_create", true);
                    }
                    Files.createDirectories(parent);
                }
                launcher = requested;
            }
            String target;
            if (WINDOWS) {
                target = installWindows(projectRoot, launcher);
            } else if (MAC) {
                target = installMac(projectRoot, launcher);
            } else {
                target = installLinux(projectRoot, launcher);
            }
            return Json.map(
                    "ok", true,
                    "platform", WINDOWS ? "windows" : MAC ? "macos" : "linux",
                    "desktop", desktop.toString(),
                    "path", target,
                    "target", launcherTarget(projectRoot).toString(),
                    "message", "Launcher created · " + target);
        } catch (Exception ex) {
            return Json.map("ok", false,
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    public Path desktopDir() {
        return FileService.desktopDir();
    }

    private String launcherName() {
        if (WINDOWS) {
            return "CodeForge AI.lnk";
        }
        if (MAC) {
            return "CodeForge AI.command";
        }
        return "codeforge-ai.desktop";
    }

    private static boolean launcherExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".lnk") || lower.endsWith(".cmd") || lower.endsWith(".bat")
                || lower.endsWith(".desktop") || lower.endsWith(".command")
                || lower.endsWith(".sh") || lower.endsWith(".exe");
    }

    private Path launcherTarget(Path projectRoot) {
        Path exe = projectRoot.resolve("native").resolve("bin")
                .resolve(WINDOWS ? "CodeForge.exe" : "CodeForge");
        return Files.isRegularFile(exe) ? exe : projectRoot;
    }

    private String installWindows(Path projectRoot, Path launcher) throws Exception {
        Path exe = projectRoot.resolve("native").resolve("bin").resolve("CodeForge.exe");
        if (!Files.isRegularFile(exe)) {
            return installWindowsFallback(projectRoot, launcher);
        }
        Path lnk = launcher.toString().toLowerCase(Locale.ROOT).endsWith(".lnk")
                ? launcher : Path.of(launcher + ".lnk");
        String script = "$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8; "
                + "$ws = New-Object -ComObject WScript.Shell; "
                + "$s = $ws.CreateShortcut('" + ps(lnk.toString()) + "'); "
                + "$s.TargetPath = '" + ps(exe.toString()) + "'; "
                + "$s.WorkingDirectory = '" + ps(projectRoot.toString()) + "'; "
                + "$s.IconLocation = '" + ps(exe.toString()) + ",0'; "
                + "$s.Description = 'CodeForge AI - local intelligent dev workbench'; "
                + "$s.Save();";
        Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", script)
                .redirectErrorStream(true)
                .start();
        boolean done = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (!done) {
            process.destroyForcibly();
            throw new IOException("PowerShell timed out while creating the shortcut");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(lnk)) {
            throw new IOException("Shortcut creation failed" + (output.isBlank() ? "" : ": " + output));
        }
        return lnk.toString();
    }

    private String installWindowsFallback(Path projectRoot, Path launcher) throws IOException {
        Path cmd = launcher.toString().toLowerCase(Locale.ROOT).endsWith(".lnk")
                ? Path.of(launcher.toString().substring(0, launcher.toString().length() - 4) + ".cmd")
                : launcher;
        String content = "@echo off\r\n"
                + "cd /d \"" + projectRoot + "\"\r\n"
                + "python aux-python\\bootstrap.py --run\r\n"
                + "if errorlevel 1 pause\r\n";
        // cmd.exe parses .cmd files with the active ANSI code page, so a UTF-8
        // script would garble a project path containing non-ASCII characters.
        Files.writeString(cmd, content, nativeCharset());
        return cmd.toString();
    }

    private static java.nio.charset.Charset nativeCharset() {
        String name = System.getProperty("sun.jnu.encoding", "");
        try {
            if (!name.isBlank()) {
                return java.nio.charset.Charset.forName(name);
            }
        } catch (Exception ignored) {
        }
        return java.nio.charset.Charset.forName("GBK");
    }

    private String installMac(Path projectRoot, Path launcher) throws IOException {
        Path command = launcher.toString().endsWith(".command")
                ? launcher : Path.of(launcher + ".command");
        String script = "#!/bin/zsh\n"
                + "cd " + sh(projectRoot.toString()) + " || exit 1\n"
                + "exec python3 aux-python/bootstrap.py --run\n";
        Files.writeString(command, script, StandardCharsets.UTF_8);
        setExecutable(command);
        return command.toString();
    }

    private String installLinux(Path projectRoot, Path launcher) throws IOException {
        Path script = projectRoot.resolve("native").resolve("bin").resolve("codeforge-launch.sh");
        Files.createDirectories(script.getParent());
        if (!Files.isRegularFile(script)) {
            String content = "#!/bin/sh\n"
                    + "ROOT=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")/../..\" && pwd)\"\n"
                    + "cd \"$ROOT\" || exit 1\n"
                    + "exec python3 aux-python/bootstrap.py --run\n";
            Files.writeString(script, content, StandardCharsets.UTF_8);
        }
        setExecutable(script);
        Path entry = launcher.toString().endsWith(".desktop")
                ? launcher : Path.of(launcher + ".desktop");
        String text = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=CodeForge AI\n"
                + "Comment=Local-first intelligent dev workbench\n"
                + "Exec=" + desktopExec(script.toString()) + "\n"
                + "Path=" + projectRoot + "\n"
                + "Terminal=true\n"
                + "Categories=Development;IDE;\n"
                + "StartupNotify=true\n";
        Files.writeString(entry, text, StandardCharsets.UTF_8);
        setExecutable(entry);
        return entry.toString();
    }

    private static void setExecutable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                    EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static String desktopExec(String script) {
        if (script.contains(" ")) {
            return "\"" + script.replace("\"", "\\\"") + "\"";
        }
        return script;
    }

    private static String ps(String value) {
        return value.replace("'", "''");
    }

    private static String sh(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
