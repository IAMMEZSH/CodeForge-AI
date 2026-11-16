package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes generated files to any absolute path the user chooses. Because the
 * server is loopback-only and origin-checked, the path is intentionally not
 * sandboxed: saving next to an existing project is a first-class feature.
 */
public final class FileService {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final Path root;

    public FileService(Path root) {
        this.root = root;
    }

    public Map<String, Object> write(Map<String, Object> request) {
        String raw = Json.str(request, "path", "").trim();
        String content = Json.str(request, "content", "");
        String filename = Json.str(request, "filename", "").trim();
        boolean createDirs = Json.bool(request, "create_dirs", false);
        String encoding = Json.str(request, "encoding", "utf8").trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return Json.map("ok", false, "error", "A destination path is required");
        }
        try {
            Path requested = resolvePath(raw);
            if (!filename.isEmpty()) {
                String nameError = nameProblem(filename);
                if (nameError != null) {
                    return Json.map("ok", false, "error", nameError);
                }
                requested = requested.resolve(filename);
            } else if (asksForDirectory(raw) || Files.isDirectory(requested)) {
                return Json.map("ok", false,
                        "error", "The path points at a folder. Provide a file name or a full file path.",
                        "path", requested.toString(),
                        "is_directory", true);
            }
            Path parent = requested.getParent();
            if (parent == null) {
                return Json.map("ok", false, "error", "The destination path is not a file path");
            }
            String nameError = nameProblem(requested.getFileName().toString());
            if (nameError != null) {
                return Json.map("ok", false, "error", nameError, "path", requested.toString());
            }
            boolean createdDirectory = false;
            if (!Files.isDirectory(parent)) {
                if (Files.exists(parent)) {
                    return Json.map("ok", false,
                            "error", "The parent path is a file, not a folder: " + parent,
                            "path", parent.toString());
                }
                if (!createDirs) {
                    return Json.map("ok", false,
                            "error", "The folder does not exist: " + parent
                                    + ". Confirm creating it, then save again.",
                            "path", parent.toString(),
                            "missing_directory", parent.toString(),
                            "can_create", true);
                }
                Files.createDirectories(parent);
                createdDirectory = true;
            }
            if (Files.isDirectory(requested)) {
                return Json.map("ok", false,
                        "error", "A folder already exists at that file path: " + requested,
                        "path", requested.toString(),
                        "is_directory", true);
            }
            byte[] bytes;
            if ("base64".equals(encoding)) {
                try {
                    bytes = Base64.getDecoder().decode(content.replaceAll("\\s", ""));
                } catch (IllegalArgumentException ex) {
                    return Json.map("ok", false, "error", "The base64 content is not valid");
                }
            } else {
                bytes = content.getBytes(StandardCharsets.UTF_8);
            }
            Files.write(requested, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return Json.map(
                    "ok", true,
                    "path", requested.toString(),
                    "directory", parent.toString(),
                    "filename", requested.getFileName().toString(),
                    "bytes", bytes.length,
                    "encoding", encoding,
                    "created_directory", createdDirectory,
                    "message", "Saved " + bytes.length + " bytes to " + requested);
        } catch (InvalidPathException ex) {
            return Json.map("ok", false,
                    "error", "The path is not valid on this system: " + ex.getInput());
        } catch (IOException ex) {
            return Json.map("ok", false,
                    "error", "Could not write " + raw + ": " + message(ex),
                    "path", raw);
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public Map<String, Object> writeBatch(Map<String, Object> request) {
        String raw = Json.str(request, "path", "").trim();
        boolean createDirs = Json.bool(request, "create_dirs", false);
        String encoding = Json.str(request, "encoding", "utf8").trim().toLowerCase(Locale.ROOT);
        List<Object> files = Json.asList(request.get("files"));
        if (files.isEmpty()) {
            return Json.map("ok", false, "error", "No files were supplied");
        }
        try {
            Path directory = null;
            boolean createdDirectory = false;
            if (!raw.isEmpty()) {
                directory = resolvePath(raw);
                if (Files.exists(directory) && !Files.isDirectory(directory)) {
                    return Json.map("ok", false,
                            "error", "The destination points at a file, not a folder: " + directory,
                            "path", directory.toString());
                }
                if (!Files.isDirectory(directory)) {
                    if (!createDirs) {
                        return Json.map("ok", false,
                                "error", "The folder does not exist: " + directory
                                        + ". Confirm creating it, then save again.",
                                "path", directory.toString(),
                                "missing_directory", directory.toString(),
                                "can_create", true);
                    }
                    Files.createDirectories(directory);
                    createdDirectory = true;
                }
            }
            List<Object> written = new ArrayList<>();
            List<Object> failures = new ArrayList<>();
            Set<String> folders = new LinkedHashSet<>();
            long total = 0;
            for (Object rawFile : files) {
                Map<String, Object> file = Json.asObject(rawFile);
                String name = Json.str(file, "name", "").trim().replace('\\', '/');
                String filePath = Json.str(file, "path", "").trim();
                String content = Json.str(file, "content", "");
                boolean fileCreateDirs = Json.bool(file, "create_dirs", createDirs);
                if (name.isEmpty() && filePath.isEmpty()) {
                    failures.add(Json.map("name", "", "error", "Missing file name or destination path"));
                    continue;
                }
                Path target;
                try {
                    if (!filePath.isEmpty()) {
                        Path expanded = expandPath(filePath);
                        if (expanded.isAbsolute()) {
                            target = expanded.normalize().toAbsolutePath();
                        } else if (directory != null) {
                            String nameError = relativeNameProblem(filePath.replace('\\', '/'));
                            if (nameError != null) {
                                failures.add(Json.map("name", name, "path", filePath, "error", nameError));
                                continue;
                            }
                            target = directory.resolve(filePath.replace('\\', '/')).normalize();
                            if (!target.startsWith(directory) || target.equals(directory)) {
                                failures.add(Json.map("name", name, "path", filePath,
                                        "error", "Refusing to write outside the destination folder"));
                                continue;
                            }
                        } else {
                            failures.add(Json.map("name", name, "path", filePath,
                                    "error", "A relative path needs a destination folder"));
                            continue;
                        }
                    } else if (directory != null) {
                        String nameError = relativeNameProblem(name);
                        if (nameError != null) {
                            failures.add(Json.map("name", name, "error", nameError));
                            continue;
                        }
                        target = directory.resolve(name).normalize();
                        if (!target.startsWith(directory) || target.equals(directory)) {
                            failures.add(Json.map("name", name,
                                    "error", "Refusing to write outside the destination folder"));
                            continue;
                        }
                    } else {
                        failures.add(Json.map("name", name,
                                "error", "Give a destination folder or an absolute file path"));
                        continue;
                    }
                } catch (InvalidPathException ex) {
                    failures.add(Json.map("name", name, "path", filePath,
                            "error", "The path is not valid on this system: " + ex.getInput()));
                    continue;
                }
                if (target.equals(directory)) {
                    failures.add(Json.map("name", name, "path", target.toString(),
                            "error", "The file path points at the destination folder itself"));
                    continue;
                }
                Path parent = target.getParent();
                if (parent == null) {
                    failures.add(Json.map("name", name, "path", target.toString(),
                            "error", "The destination path is not a file path"));
                    continue;
                }
                String nameError = nameProblem(target.getFileName().toString());
                if (nameError != null) {
                    failures.add(Json.map("name", name, "path", target.toString(), "error", nameError));
                    continue;
                }
                if (!Files.isDirectory(parent)) {
                    if (Files.exists(parent)) {
                        failures.add(Json.map("name", name, "path", target.toString(),
                                "error", "The parent path is a file, not a folder: " + parent));
                        continue;
                    }
                    if (!fileCreateDirs) {
                        failures.add(Json.map("name", name, "path", target.toString(),
                                "error", "The folder does not exist: " + parent,
                                "missing_directory", parent.toString(),
                                "can_create", true));
                        continue;
                    }
                    Files.createDirectories(parent);
                }
                if (Files.isDirectory(target)) {
                    failures.add(Json.map("name", name, "path", target.toString(),
                            "error", "A folder already exists at that file path"));
                    continue;
                }
                try {
                    byte[] bytes = "base64".equals(encoding)
                            ? Base64.getDecoder().decode(content.replaceAll("\\s", ""))
                            : content.getBytes(StandardCharsets.UTF_8);
                    Files.write(target, bytes, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    total += bytes.length;
                    folders.add(parent.toString());
                    written.add(Json.map(
                            "name", name.isEmpty() ? target.getFileName().toString() : name,
                            "path", target.toString(),
                            "parent", parent.toString(),
                            "bytes", bytes.length));
                } catch (Exception ex) {
                    failures.add(Json.map("name", name, "path", target.toString(),
                            "error", message(ex)));
                }
            }
            boolean ok = failures.isEmpty() && !written.isEmpty();
            Map<String, Object> result = Json.map(
                    "ok", ok,
                    "path", directory != null ? directory.toString() : "",
                    "directories", new ArrayList<>(folders),
                    "bytes", total,
                    "count", written.size(),
                    "created_directory", createdDirectory,
                    "files", written,
                    "failures", failures,
                    "message", ok
                            ? "Saved " + written.size() + " file(s) · " + total + " bytes"
                            : (written.isEmpty()
                                    ? "No files were written"
                                    : "Saved " + written.size() + " file(s), "
                                            + failures.size() + " failed"));
            if (!ok) {
                result.put("error", written.isEmpty()
                        ? "No files could be written"
                        : "Some files could not be written");
            }
            return result;
        } catch (InvalidPathException ex) {
            return Json.map("ok", false,
                    "error", "The path is not valid on this system: " + ex.getInput());
        } catch (IOException ex) {
            return Json.map("ok", false,
                    "error", "Could not create " + raw + ": " + message(ex),
                    "path", raw);
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public Map<String, Object> place(Map<String, Object> request) {
        String sourceRaw = Json.str(request, "source", "").trim();
        String destinationRaw = Json.str(request, "destination", "").trim();
        String driveRoot = driveRootAlias(destinationRaw);
        if (driveRoot != null) destinationRaw = driveRoot;
        String name = Json.str(request, "name", "").trim();
        boolean overwrite = Json.bool(request, "overwrite", false);
        boolean createDirs = Json.bool(request, "create_dirs", true);
        String mode = Json.str(request, "mode", "copy").trim().toLowerCase(Locale.ROOT);
        boolean move = "move".equals(mode);
        if (!move && !"copy".equals(mode)) {
            return Json.map("ok", false, "error", "mode must be copy or move");
        }
        if (sourceRaw.isEmpty()) {
            return Json.map("ok", false, "error", "A source file or folder is required");
        }
        long started = System.currentTimeMillis();
        try {
            Path source = resolvePath(sourceRaw);
            if (!Files.exists(source)) {
                return Json.map("ok", false,
                        "error", "That source path does not exist: " + source,
                        "source", source.toString());
            }
            Path sourceName = source.getFileName();
            if (sourceName == null) {
                return Json.map("ok", false,
                        "error", "The source is a drive root. Pick a file or folder inside it.",
                        "source", source.toString());
            }
            boolean folder = Files.isDirectory(source);
            if (source.toAbsolutePath().normalize()
                    .equals(root.toAbsolutePath().normalize())) {
                return Json.map("ok", false,
                        "error", "That source is the running project. Use Place project anywhere "
                                + "(or /place) so models, runtime and logs are not copied.",
                        "source", source.toString());
            }
            Path destination = destinationRaw.isEmpty() ? desktopDir() : resolvePath(destinationRaw);
            Path destinationRoot = destination.getRoot();
            if (destinationRoot != null && !Files.exists(destinationRoot)) {
                return Json.map("ok", false,
                        "error", "That drive or network location does not exist: " + destinationRoot,
                        "destination", destination.toString());
            }
            boolean folderHint = destinationRaw.endsWith("/") || destinationRaw.endsWith("\\");
            Path target;
            if (!name.isEmpty()) {
                String nameError = nameProblem(name);
                if (nameError != null) {
                    return Json.map("ok", false, "error", nameError);
                }
                if (Files.exists(destination) && !Files.isDirectory(destination)) {
                    return Json.map("ok", false,
                            "error", "The destination points at a file, not a folder: " + destination,
                            "destination", destination.toString());
                }
                target = destination.resolve(name);
            } else if (Files.isDirectory(destination)) {
                target = destination.resolve(sourceName.toString());
            } else if (Files.isRegularFile(destination)) {
                if (folder) {
                    return Json.map("ok", false,
                            "error", "The destination is a file, but the source is a folder: "
                                    + destination,
                            "destination", destination.toString());
                }
                target = destination;
            } else {
                String destinationName = destination.getFileName() == null
                        ? "" : destination.getFileName().toString();
                if (folder) {
                    target = folderHint ? destination.resolve(sourceName.toString()) : destination;
                } else if (folderHint || !looksLikeFileName(destinationName)) {
                    target = destination.resolve(sourceName.toString());
                } else {
                    target = destination;
                }
            }
            source = source.toAbsolutePath().normalize();
            target = target.toAbsolutePath().normalize();
            if (target.equals(source)) {
                return Json.map("ok", false,
                        "error", "The source is already at that path. Choose a different destination.",
                        "path", target.toString());
            }
            if (folder && target.startsWith(source)) {
                return Json.map("ok", false,
                        "error", "The destination is inside the source folder. Choose a folder outside "
                                + source + ".",
                        "path", target.toString());
            }
            if (Files.exists(target) && folder != Files.isDirectory(target)) {
                return Json.map("ok", false,
                        "error", "The destination already exists with a different type: " + target,
                        "path", target.toString(),
                        "exists", true);
            }
            Path parent = target.getParent();
            if (parent == null) {
                return Json.map("ok", false, "error", "The destination is not a file or folder path");
            }
            Counter counter = new Counter();
            if (Files.exists(target) && !overwrite) {
                List<String> conflicts = new ArrayList<>();
                if (folder) {
                    scanTree(source, target, counter, conflicts);
                } else {
                    scanFile(source, target, counter, conflicts);
                }
                if (!conflicts.isEmpty()) {
                    String first = conflicts.get(0);
                    String suffix = conflicts.size() > 1
                            ? " (and " + (conflicts.size() - 1) + " more)" : "";
                    return Json.map("ok", false,
                            "error", "Already exists: " + first + suffix
                                    + ". Enable overwrite or reply with 确认覆盖 to replace it.",
                            "path", target.toString(),
                            "exists", true,
                            "can_overwrite", true,
                            "conflicts", conflicts.size());
                }
            }
            boolean createdDirectory = false;
            if (!Files.isDirectory(parent)) {
                if (Files.exists(parent)) {
                    return Json.map("ok", false,
                            "error", "The parent path is a file, not a folder: " + parent,
                            "destination", parent.toString());
                }
                if (!createDirs) {
                    return Json.map("ok", false,
                            "error", "The folder does not exist: " + parent
                                    + ". Confirm creating it, then place again.",
                            "destination", parent.toString(),
                            "missing_directory", parent.toString(),
                            "can_create", true);
                }
                Files.createDirectories(parent);
                createdDirectory = true;
            }
            counter = new Counter();
            if (folder) {
                copyTree(source, target, counter, overwrite);
            } else {
                copyFile(source, target, counter, overwrite);
            }
            if (move) {
                try {
                    deleteTree(source);
                } catch (IOException ex) {
                    return Json.map("ok", false,
                            "error", "Copied to " + target + ", but could not remove the source "
                                    + source + ": " + message(ex),
                            "source", source.toString(),
                            "path", target.toString(),
                            "copied", true,
                            "moved", false);
                }
            }
            long duration = System.currentTimeMillis() - started;
            return Json.map(
                    "ok", true,
                    "source", source.toString(),
                    "path", target.toString(),
                    "kind", folder ? "folder" : "file",
                    "mode", move ? "move" : "copy",
                    "moved", move,
                    "files", counter.files,
                    "bytes", counter.bytes,
                    "unchanged", counter.unchanged,
                    "overwritten", counter.overwritten,
                    "created_directory", createdDirectory,
                    "duration_ms", duration,
                    "directory", folder ? target.toString() : parent.toString(),
                    "message", (move ? "Moved " : "Copied ") + (folder ? "folder" : "file")
                            + " to " + target + " · " + counter.files + " file(s) · "
                            + counter.bytes + " bytes");
        } catch (InvalidPathException ex) {
            return Json.map("ok", false,
                    "error", "The path is not valid on this system: " + ex.getInput());
        } catch (IOException ex) {
            String hint = ex instanceof AccessDeniedException
                    ? " Permission denied — choose a writable folder. Drive roots such as C:\\ "
                            + "usually require an elevated app."
                    : "";
            return Json.map("ok", false,
                    "error", (move ? "Could not move " : "Could not place ") + sourceRaw
                            + ": " + message(ex) + hint,
                    "source", sourceRaw);
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    private static void scanFile(Path source, Path target, Counter counter, List<String> conflicts) {
        if (sameFile(source, target)) {
            counter.unchanged++;
        } else {
            conflicts.add(target.toString());
        }
    }

    private static void scanTree(Path source, Path target, Counter counter,
                                 List<String> conflicts) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path output = target.resolve(source.relativize(file).toString());
                if (!Files.exists(output)) {
                    counter.missing++;
                } else if (!Files.isRegularFile(output) || !sameFile(file, output)) {
                    conflicts.add(output.toString());
                } else {
                    counter.unchanged++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyFile(Path source, Path target, Counter counter,
                                 boolean overwrite) throws IOException {
        boolean existed = Files.exists(target);
        long size = Files.size(source);
        if (existed && sameFile(source, target)) {
            counter.files++;
            counter.bytes += size;
            counter.unchanged++;
            return;
        }
        copyWithRetry(source, target, overwrite);
        counter.files++;
        counter.bytes += size;
        if (existed) {
            counter.overwritten++;
        }
    }

    private static void copyTree(Path source, Path target, Counter counter,
                                 boolean overwrite) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path output = target.resolve(source.relativize(dir).toString());
                Files.createDirectories(output);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path output = target.resolve(source.relativize(file).toString());
                boolean existed = Files.exists(output);
                if (existed && sameFile(file, output)) {
                    counter.files++;
                    counter.bytes += attrs.size();
                    counter.unchanged++;
                    return FileVisitResult.CONTINUE;
                }
                copyWithRetry(file, output, overwrite);
                counter.files++;
                counter.bytes += attrs.size();
                if (existed) {
                    counter.overwritten++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyWithRetry(Path source, Path target, boolean overwrite)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (overwrite) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return;
            } catch (IOException ex) {
                last = ex;
                try {
                    Thread.sleep(80L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last;
    }

    private static void deleteTree(Path source) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean sameFile(Path source, Path target) {
        try {
            return Files.size(source) == Files.size(target)
                    && Math.abs(Files.getLastModifiedTime(source).toMillis()
                    - Files.getLastModifiedTime(target).toMillis()) < 2000L;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean looksLikeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.startsWith(".") && name.length() > 1) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 && name.length() - dot - 1 <= 12;
    }

    private static final class Counter {
        long files;
        long bytes;
        long unchanged;
        long missing;
        long overwritten;
    }

    public Map<String, Object> reveal(Map<String, Object> request) {
        String raw = Json.str(request, "path", "").trim();
        if (raw.isEmpty()) {
            return Json.map("ok", false, "error", "A path is required");
        }
        try {
            Path path = resolvePath(raw);
            if (!Files.exists(path)) {
                return Json.map("ok", false,
                        "error", "That path does not exist: " + path,
                        "path", path.toString());
            }
            List<String> command = new ArrayList<>();
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                command.add("explorer.exe");
                if (Files.isDirectory(path)) {
                    command.add(path.toString());
                } else {
                    command.add("/select," + path);
                }
            } else if (os.contains("mac")) {
                command.add("open");
                command.add(Files.isDirectory(path) ? path.toString() : "-R");
                if (!Files.isDirectory(path)) {
                    command.add(path.toString());
                }
            } else {
                command.add("xdg-open");
                command.add(Files.isDirectory(path) ? path.toString() : path.getParent().toString());
            }
            new ProcessBuilder(command).start();
            return Json.map("ok", true, "path", path.toString());
        } catch (InvalidPathException ex) {
            return Json.map("ok", false,
                    "error", "The path is not valid on this system: " + ex.getInput());
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public static String driveRootAlias(String raw) {
        if (!WINDOWS) return null;
        String text = raw == null ? "" : raw.trim().replaceAll("[，。；;、]+$", "").trim();
        if (!text.matches("(?i)^[a-z](?:\\s*盘)?(?:\\s*根目录)?\\s*[:：]?$")) return null;
        return Character.toUpperCase(text.charAt(0)) + ":\\";
    }

    public Path resolvePath(String raw) {
        String alias = raw == null ? "" : raw.trim();
        if (alias.length() >= 2 && alias.startsWith("\"") && alias.endsWith("\"")) {
            alias = alias.substring(1, alias.length() - 1).trim();
        }
        while (alias.endsWith("/") || alias.endsWith("\\")) {
            alias = alias.substring(0, alias.length() - 1).trim();
        }
        if (alias.equalsIgnoreCase("desktop") || alias.equals("桌面")) {
            return desktopDir().toAbsolutePath();
        }
        if (alias.matches("(?i)^(?:desktop|桌面)[\\\\/].+")) {
            int slash = Math.max(alias.indexOf('/'), alias.indexOf('\\'));
            return desktopDir().resolve(alias.substring(slash + 1))
                    .normalize().toAbsolutePath();
        }
        Path path = expandPath(raw);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    public static Path desktopDir() {
        if (WINDOWS) {
            String profile = System.getenv("USERPROFILE");
            if (profile != null && !profile.isBlank()) {
                Path desktop = Path.of(profile, "Desktop");
                if (Files.isDirectory(desktop)) {
                    return desktop;
                }
            }
            String oneDrive = System.getenv("OneDrive");
            if (oneDrive != null && !oneDrive.isBlank()) {
                Path desktop = Path.of(oneDrive, "Desktop");
                if (Files.isDirectory(desktop)) {
                    return desktop;
                }
            }
            return Path.of(System.getProperty("user.home", ""), "Desktop");
        }
        if (!MAC) {
            Path configured = xdgDesktop();
            if (configured != null && Files.isDirectory(configured)) {
                return configured;
            }
        }
        return Path.of(System.getProperty("user.home", ""), "Desktop");
    }

    private static Path xdgDesktop() {
        Path config = Path.of(System.getProperty("user.home", ""), ".config", "user-dirs.dirs");
        if (!Files.isRegularFile(config)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("XDG_DESKTOP_DIR=")) {
                    continue;
                }
                String value = trimmed.substring("XDG_DESKTOP_DIR=".length()).trim();
                value = value.replace("\"", "").replace("$HOME",
                        System.getProperty("user.home", ""));
                if (!value.isBlank()) {
                    return Path.of(value);
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static Path expandPath(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("~")) {
            String home = System.getProperty("user.home", "");
            value = value.equals("~") ? home : home + value.substring(1);
        }
        return Path.of(value);
    }

    private static boolean asksForDirectory(String raw) {
        return raw.endsWith("/") || raw.endsWith("\\");
    }

    private static String nameProblem(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
            return "The file name is not valid";
        }
        if (WINDOWS) {
            if (trimmed.matches(".*[<>:\"|?*\\x00-\\x1f].*")) {
                return "The file name contains characters Windows does not allow: " + name;
            }
            if (trimmed.endsWith(".") || trimmed.endsWith(" ")) {
                return "Windows file names cannot end with a dot or a space: " + name;
            }
            String base = trimmed.contains(".")
                    ? trimmed.substring(0, trimmed.indexOf('.')).toUpperCase(Locale.ROOT)
                    : trimmed.toUpperCase(Locale.ROOT);
            if (base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                return "That name is reserved by Windows: " + name;
            }
        }
        return null;
    }

    private static String relativeNameProblem(String relative) {
        String[] parts = relative.split("/");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            String problem = nameProblem(part);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
