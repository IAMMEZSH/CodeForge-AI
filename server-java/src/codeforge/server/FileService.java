package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    public Path resolvePath(String raw) {
        Path path = expandPath(raw);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.normalize().toAbsolutePath();
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
