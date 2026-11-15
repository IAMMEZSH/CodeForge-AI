package codeforge.server;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies the runnable project tree to any user-chosen path. Heavy local state
 * (models, runtime, workspace, logs, memory, build caches) is never copied.
 * The portable JDK and downloaded toolchains are optional because both can be
 * re-provisioned by bootstrap.py or the System page.
 */
public final class ProjectExporter {
    private static final Set<String> EXCLUDED_TOP = Set.of(
            "build", "release", "models", "runtime", "workspace", "logs", "memory",
            ".git", ".qoder", ".idea", ".vscode");
    private static final Set<String> EXCLUDED_ANY = Set.of(
            "__pycache__", "node_modules", ".pytest_cache", "obj", ".cache");
    private static final Set<String> BUILD_KEEP = Set.of("CodeForgeServer.jar");

    private final Path root;
    private final FileService files;
    private final DesktopIntegration desktop;

    public ProjectExporter(Path root, FileService files, DesktopIntegration desktop) {
        this.root = root;
        this.files = files;
        this.desktop = desktop;
    }

    public Map<String, Object> place(Map<String, Object> request) {
        boolean includeJdk = Json.bool(request, "include_jdk", false);
        boolean includeToolchains = Json.bool(request, "include_toolchains", false);
        boolean shortcut = Json.bool(request, "shortcut", true);
        String raw = Json.str(request, "path", "").trim();
        try {
            Path target;
            if (raw.isEmpty()) {
                target = desktop.desktopDir().resolve("CodeForge-AI");
            } else {
                Path requested = files.resolvePath(raw);
                target = requested;
                if (Files.exists(target) && Files.isRegularFile(target)) {
                    return Json.map("ok", false,
                            "error", "The destination points at a file, not a directory");
                }
                if (Files.isDirectory(target) && !isEmpty(target)
                        && !"codeforge-ai".equals(fileName(target))) {
                    target = target.resolve("CodeForge-AI");
                }
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            target = target.toAbsolutePath().normalize();
            if (target.equals(normalizedRoot)) {
                return Json.map("ok", false,
                        "error", "The project is already at that path. Choose another folder, "
                        + "or create a desktop shortcut instead.");
            }
            if (target.startsWith(normalizedRoot)) {
                return Json.map("ok", false,
                        "error", "The destination is inside the project. Choose a folder outside "
                        + normalizedRoot + ".");
            }
            if (Files.isRegularFile(target)) {
                return Json.map("ok", false, "error", "The destination is a file");
            }
            Path targetRoot = target.getRoot();
            if (targetRoot != null && !Files.exists(targetRoot)) {
                return Json.map("ok", false,
                        "error", "That drive or network location does not exist: " + targetRoot,
                        "path", target.toString());
            }
            Files.createDirectories(target);
            long started = System.currentTimeMillis();
            Counter counter = new Counter();
            final Path destination = target;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && skipDirectory(dir, includeJdk, includeToolchains)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path relative = root.relativize(dir);
                    if (!relative.toString().isEmpty()) {
                        try {
                            Files.createDirectories(destination.resolve(relative));
                        } catch (IOException ex) {
                            counter.error = "Cannot create " + destination.resolve(relative)
                                    + ": " + ex.getMessage();
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (skipFile(file, includeJdk, includeToolchains)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = root.relativize(file);
                    Path output = destination.resolve(relative);
                    try {
                        Files.createDirectories(output.getParent());
                        Files.copy(file, output, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        counter.files++;
                        counter.bytes += attrs.size();
                    } catch (IOException ex) {
                        counter.error = "Cannot copy " + relative + ": " + ex.getMessage();
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    counter.skipped++;
                    return FileVisitResult.CONTINUE;
                }
            });
            if (counter.error != null) {
                return Json.map("ok", false, "error", counter.error,
                        "path", target.toString(),
                        "files", counter.files,
                        "bytes", counter.bytes);
            }
            String shortcutPath = "";
            if (shortcut) {
                Map<String, Object> created = desktop.installForProject(target,
                        Json.map("path", target.toString()));
                if (Boolean.TRUE.equals(created.get("ok"))) {
                    shortcutPath = String.valueOf(created.get("path"));
                }
            }
            return Json.map(
                    "ok", true,
                    "path", target.toString(),
                    "files", counter.files,
                    "bytes", counter.bytes,
                    "skipped", counter.skipped,
                    "duration_ms", System.currentTimeMillis() - started,
                    "include_jdk", includeJdk,
                    "include_toolchains", includeToolchains,
                    "shortcut", shortcutPath,
                    "message", "Project placed at " + target + " · " + counter.files
                            + " files · " + human(counter.bytes)
                            + (shortcutPath.isBlank() ? "" : " · launcher " + shortcutPath));
        } catch (Exception ex) {
            return Json.map("ok", false,
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private boolean skipDirectory(Path dir, boolean includeJdk, boolean includeToolchains) {
        Path relative = root.relativize(dir);
        String first = relative.getName(0).toString().toLowerCase(Locale.ROOT);
        if (EXCLUDED_TOP.contains(first)) {
            return true;
        }
        for (Path part : relative) {
            if (EXCLUDED_ANY.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (!includeJdk && relative.startsWith(Path.of("native", "jdk"))) {
            return true;
        }
        if (!includeToolchains && relative.startsWith(Path.of("native", "toolchains"))) {
            return true;
        }
        if (relative.startsWith(Path.of("server-java", "build"))) {
            return !relative.equals(Path.of("server-java", "build"));
        }
        return false;
    }

    private boolean skipFile(Path file, boolean includeJdk, boolean includeToolchains) {
        Path relative = root.relativize(file);
        String first = relative.getName(0).toString().toLowerCase(Locale.ROOT);
        if (EXCLUDED_TOP.contains(first)) {
            return true;
        }
        for (Path part : relative) {
            if (EXCLUDED_ANY.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (!includeJdk && relative.startsWith(Path.of("native", "jdk"))) {
            return true;
        }
        if (!includeToolchains && relative.startsWith(Path.of("native", "toolchains"))) {
            return true;
        }
        if (relative.startsWith(Path.of("server-java", "build"))) {
            return !BUILD_KEEP.contains(file.getFileName().toString());
        }
        return false;
    }

    private static boolean isEmpty(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (IOException ex) {
            return false;
        }
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[index - 1]);
    }

    private static final class Counter {
        long files;
        long bytes;
        long skipped;
        String error;
    }
}
