package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Permission-gated whole-disk file access for the super agent: search,
 * list, stat, create folders, rename, copy, move and safe delete. The
 * permission is stored in config.json and must be enabled by the user.
 * Destructive operations are moved to a trash folder unless the caller
 * explicitly confirms a permanent delete.
 */
public final class FileAccessService {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final long MAX_SEARCH_MS = 90_000;
    private static final int MAX_MATCHES = 2_000;
    private static final long MAX_CONTENT_BYTES = 2L * 1024 * 1024;
    private static final List<String> SKIP_DIRS = List.of(
            "$recycle.bin", "system volume information", "recovery",
            "windows", "winsxs", "driverstore", "assembly", "installer",
            "proc", "sys", "dev", "run", "snap", "boot", "lost+found");

    private final Path root;
    private final AppSettings settings;
    private final Path configFile;
    private final Path auditFile;

    public FileAccessService(Path root, AppSettings settings) {
        this.root = root.toAbsolutePath().normalize();
        this.settings = settings;
        this.configFile = root.resolve("config.json");
        this.auditFile = root.resolve("logs").resolve("file-access.jsonl");
    }

    public Map<String, Object> permissionView() {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> perms = Json.asObject(doc.get("permissions"));
            return Json.map(
                    "ok", true,
                    "full_filesystem", Json.bool(perms, "full_filesystem", false),
                    "config", configFile.toString(),
                    "trash", trashRoot().toString(),
                    "note", "Whole-disk access is off until you enable it here.");
        } catch (Exception ex) {
            return Json.map("ok", false, "error", "Cannot read permissions: " + ex.getMessage());
        }
    }

    public synchronized Map<String, Object> setPermission(Map<String, Object> request) {
        if (!request.containsKey("full_filesystem")) {
            return Json.map("ok", false, "error", "full_filesystem is required");
        }
        Object value = request.get("full_filesystem");
        if (!(value instanceof Boolean flag)) {
            return Json.map("ok", false, "error", "full_filesystem must be true or false");
        }
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> perms = Json.asObject(doc.get("permissions"));
            perms.put("full_filesystem", flag);
            doc.put("permissions", perms);
            Files.writeString(configFile, Json.writePretty(doc), StandardCharsets.UTF_8);
            audit(flag ? "permission-on" : "permission-off", "", "ok");
            Map<String, Object> out = new LinkedHashMap<>(permissionView());
            out.put("message", flag
                    ? "Whole-disk file access enabled. Every write is audited."
                    : "Whole-disk file access disabled.");
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", "Cannot save permissions: " + ex.getMessage());
        }
    }

    public Map<String, Object> drives() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Path drive : driveRoots()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", drive.toString());
            item.put("name", drive.toString());
            try {
                item.put("total_bytes", Files.getFileStore(drive).getTotalSpace());
                item.put("free_bytes", Files.getFileStore(drive).getUsableSpace());
            } catch (Exception ignored) {
            }
            items.add(item);
        }
        return Json.map("ok", true, "count", items.size(), "drives", items,
                "full_filesystem", fullAccess(),
                "home", System.getProperty("user.home", ""),
                "desktop", Path.of(System.getProperty("user.home", ""))
                        .resolve("Desktop").toString());
    }

    public boolean fullAccess() {
        try {
            Map<String, Object> perms = Json.asObject(readDoc().get("permissions"));
            return Json.bool(perms, "full_filesystem", false);
        } catch (Exception ex) {
            return false;
        }
    }

    public Map<String, Object> search(Map<String, Object> request) {
        if (!fullAccess()) {
            return denied("search");
        }
        String text = Json.str(request, "query", "").trim();
        String namePattern = Json.str(request, "name", "").trim();
        String content = Json.str(request, "content", "").trim();
        if (text.isEmpty() && namePattern.isEmpty() && content.isEmpty()) {
            return Json.map("ok", false, "error", "Provide query, name or content");
        }
        int limit = Math.max(1, Math.min(MAX_MATCHES,
                Json.integer(request, "limit", 200)));
        long deadline = System.currentTimeMillis() + Math.max(2_000,
                Math.min(MAX_SEARCH_MS, Json.integer(request, "timeout_sec", 45) * 1000L));
        boolean includeDirs = Json.bool(request, "include_dirs", true);
        boolean deep = Json.bool(request, "deep", false);
        List<Path> roots = new ArrayList<>();
        Object rawRoots = request.get("roots");
        if (rawRoots instanceof List<?>) {
            for (Object raw : (List<?>) rawRoots) {
                String value = raw == null ? "" : String.valueOf(raw).trim();
                if (!value.isEmpty()) {
                    roots.add(resolve(value));
                }
            }
        }
        String single = Json.str(request, "path", "").trim();
        if (!single.isEmpty()) {
            roots.add(resolve(single));
        }
        if (roots.isEmpty()) {
            for (Path drive : driveRoots()) {
                roots.add(drive);
            }
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        AtomicLong visited = new AtomicLong();
        long started = System.currentTimeMillis();
        try {
            for (Path start : roots) {
                if (matches.size() >= limit || System.currentTimeMillis() > deadline) {
                    break;
                }
                if (!Files.exists(start)) {
                    continue;
                }
                int depth = deep ? Integer.MAX_VALUE : Math.max(1,
                        Math.min(12, Json.integer(request, "depth", 6)));
                Files.walkFileTree(start, EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                        depth, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                if (matches.size() >= limit || System.currentTimeMillis() > deadline) {
                                    return FileVisitResult.TERMINATE;
                                }
                                String leaf = dir.getFileName() == null ? ""
                                        : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                                if (!dir.equals(start) && SKIP_DIRS.contains(leaf)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                if (includeDirs && !text.isEmpty() && dir.getFileName() != null) {
                                    String hay = dir.toString();
                                    if (hay.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
                                        matches.add(fileInfo(dir, "dir", "path", text));
                                    }
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (matches.size() >= limit || System.currentTimeMillis() > deadline) {
                                    return FileVisitResult.TERMINATE;
                                }
                                if (!attrs.isRegularFile()) {
                                    return FileVisitResult.CONTINUE;
                                }
                                visited.incrementAndGet();
                                String fileName = file.getFileName().toString();
                                String lower = fileName.toLowerCase(Locale.ROOT);
                                String hay = file.toString().toLowerCase(Locale.ROOT);
                                boolean hit = false;
                                String how = "";
                                if (!text.isEmpty() && hay.contains(text.toLowerCase(Locale.ROOT))) {
                                    hit = true;
                                    how = "path";
                                }
                                if (!namePattern.isEmpty() && globMatch(lower,
                                        namePattern.toLowerCase(Locale.ROOT))) {
                                    hit = true;
                                    how = how.isEmpty() ? "name" : how + "+name";
                                }
                                if (!content.isEmpty() && attrs.size() <= MAX_CONTENT_BYTES
                                        && looksText(lower)) {
                                    String excerpt = grep(file, content);
                                    if (excerpt != null) {
                                        Map<String, Object> item = fileInfo(file, "file", how.isEmpty()
                                                ? "content" : how + "+content", content);
                                        item.put("excerpt", excerpt);
                                        matches.add(item);
                                        return FileVisitResult.CONTINUE;
                                    }
                                }
                                if (hit) {
                                    matches.add(fileInfo(file, "file", how, text));
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            }
            matches.sort(Comparator.comparingLong(
                    (Map<String, Object> item) -> Json.lon(item, "modified_ms", 0)).reversed());
            Map<String, Object> out = Json.map(
                    "ok", true,
                    "query", text,
                    "name", namePattern,
                    "content", content,
                    "roots", roots.stream().map(Path::toString).toList(),
                    "visited", visited.get(),
                    "count", matches.size(),
                    "truncated", matches.size() >= limit
                            || System.currentTimeMillis() > deadline,
                    "duration_ms", System.currentTimeMillis() - started,
                    "matches", matches);
            audit("search", roots.toString(), "ok");
            return out;
        } catch (Exception ex) {
            audit("search", roots.toString(), "error: " + ex.getMessage());
            return Json.map("ok", false, "error", "Search failed: " + ex.getMessage());
        }
    }

    public Map<String, Object> operation(Map<String, Object> request) {
        if (!fullAccess()) {
            return denied("file operation");
        }
        String op = Json.str(request, "op", "").trim().toLowerCase(Locale.ROOT);
        String rawPath = Json.str(request, "path", "").trim();
        if (rawPath.isEmpty()) {
            return Json.map("ok", false, "error", "path is required");
        }
        try {
            Path path = resolve(rawPath);
            switch (op) {
                case "list":
                    return list(path, Json.integer(request, "depth", 1));
                case "stat":
                    return stat(path);
                case "mkdir": {
                    guard(path);
                    Files.createDirectories(path);
                    audit("mkdir", path.toString(), "ok");
                    return Json.map("ok", true, "path", path.toString(),
                            "message", "Folder created");
                }
                case "rename": {
                    String to = Json.str(request, "to", "").trim();
                    if (to.isEmpty()) {
                        return Json.map("ok", false, "error", "to is required");
                    }
                    Path target = resolve(to);
                    guard(path);
                    guard(target);
                    if (Files.exists(target) && !Json.bool(request, "overwrite", false)) {
                        return Json.map("ok", false, "error", "Destination already exists: " + target);
                    }
                    Files.createDirectories(target.getParent());
                    Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                    audit("rename", path + " -> " + target, "ok");
                    return Json.map("ok", true, "path", target.toString(),
                            "message", "Renamed to " + target);
                }
                case "copy":
                case "move": {
                    String to = Json.str(request, "to", "").trim();
                    if (to.isEmpty()) {
                        return Json.map("ok", false, "error", "to is required");
                    }
                    Path target = resolve(to);
                    guard(path);
                    guard(target);
                    if (Files.isDirectory(target)) {
                        target = target.resolve(path.getFileName().toString());
                    }
                    if (Files.exists(target) && !Json.bool(request, "overwrite", false)) {
                        return Json.map("ok", false, "error", "Destination already exists: " + target);
                    }
                    Files.createDirectories(target.getParent());
                    if ("move".equals(op)) {
                        if (!Json.bool(request, "confirm", false)) {
                            return Json.map("ok", false, "confirmation_required", true,
                                    "error", "Moving files changes their location. Send confirm=true.");
                        }
                        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                    } else if (Files.isDirectory(path)) {
                        copyTree(path, target);
                    } else {
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    audit(op, path + " -> " + target, "ok");
                    return Json.map("ok", true, "path", target.toString(),
                            "message", ("move".equals(op) ? "Moved to " : "Copied to ") + target);
                }
                case "delete":
                case "trash": {
                    boolean permanent = Json.bool(request, "permanent", false);
                    if (!Json.bool(request, "confirm", false)) {
                        return Json.map("ok", false, "confirmation_required", true,
                                "error", "Deleting a path needs confirm=true.");
                    }
                    guard(path);
                    if (permanent) {
                        if (!"DELETE".equals(Json.str(request, "confirm_text", ""))) {
                            return Json.map("ok", false, "confirmation_required", true,
                                    "error", "Permanent delete needs confirm_text=DELETE; "
                                            + "otherwise the file is moved to the trash folder.");
                        }
                        deleteTree(path);
                        audit("delete-permanent", path.toString(), "ok");
                        return Json.map("ok", true, "path", path.toString(),
                                "permanent", true, "message", "Deleted permanently");
                    }
                    Path trash = trashRoot();
                    Files.createDirectories(trash);
                    String name = path.getFileName() == null ? "item" : path.getFileName().toString();
                    Path target = trash.resolve(STAMP.format(Instant.now()) + "-" + name);
                    int n = 1;
                    while (Files.exists(target)) {
                        target = trash.resolve(STAMP.format(Instant.now()) + "-" + n + "-" + name);
                        ++n;
                    }
                    Files.move(path, target);
                    audit("trash", path + " -> " + target, "ok");
                    return Json.map("ok", true, "path", target.toString(),
                            "original", path.toString(), "permanent", false,
                            "message", "Moved to the trash folder; restore it manually if needed");
                }
                default:
                    return Json.map("ok", false,
                            "error", "Unknown operation. Use list/stat/mkdir/rename/copy/move/delete.");
            }
        } catch (Exception ex) {
            audit(op, rawPath, "error: " + ex.getMessage());
            return Json.map("ok", false, "error", ex.getMessage());
        }
    }

    private Map<String, Object> list(Path path, int rawDepth) throws IOException {
        if (!Files.exists(path)) {
            return Json.map("ok", false, "error", "Path not found: " + path);
        }
        if (!Files.isDirectory(path)) {
            return stat(path);
        }
        int depth = Math.max(1, Math.min(8, rawDepth));
        List<Map<String, Object>> entries = new ArrayList<>();
        try (var stream = Files.walk(path, depth)) {
            stream.forEach(item -> {
                if (entries.size() >= 1000 || item.equals(path)) {
                    return;
                }
                try {
                    boolean dir = Files.isDirectory(item);
                    entries.add(fileInfo(item, dir ? "dir" : "file", "", ""));
                } catch (Exception ignored) {
                }
            });
        }
        return Json.map("ok", true, "path", path.toString(), "count", entries.size(),
                "truncated", entries.size() >= 1000, "entries", entries);
    }

    private Map<String, Object> stat(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Json.map("ok", false, "error", "Path not found: " + path);
        }
        Map<String, Object> out = new LinkedHashMap<>(fileInfo(path,
                Files.isDirectory(path) ? "dir" : "file", "", ""));
        out.put("ok", true);
        out.put("readable", Files.isReadable(path));
        out.put("writable", Files.isWritable(path));
        out.put("executable", Files.isExecutable(path));
        if (Files.isRegularFile(path)) {
            out.put("sha256", sha256(path));
        }
        return out;
    }

    private Map<String, Object> fileInfo(Path path, String type, String how, String query) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", path.toAbsolutePath().normalize().toString());
        item.put("name", path.getFileName() == null ? path.toString()
                : path.getFileName().toString());
        item.put("type", type);
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            item.put("bytes", attrs.size());
            item.put("modified_ms", attrs.lastModifiedTime().toMillis());
            item.put("modified", STAMP.format(Instant.ofEpochMilli(
                    attrs.lastModifiedTime().toMillis())));
        } catch (Exception ignored) {
        }
        if (!how.isEmpty()) {
            item.put("matched", how);
        }
        return item;
    }

    private String grep(Path file, String needle) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            int index = text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
            if (index < 0) {
                return null;
            }
            int start = Math.max(0, index - 120);
            int end = Math.min(text.length(), index + needle.length() + 180);
            return text.substring(start, end).replaceAll("\\s+", " ").trim();
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean looksText(String lower) {
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".java")
                || lower.endsWith(".c") || lower.endsWith(".h") || lower.endsWith(".cpp")
                || lower.endsWith(".cs") || lower.endsWith(".py") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".csv")
                || lower.endsWith(".log") || lower.endsWith(".ini") || lower.endsWith(".toml")
                || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".kt")
                || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".php")
                || lower.endsWith(".rb") || lower.endsWith(".sh") || lower.endsWith(".bat")
                || lower.endsWith(".ps1") || lower.endsWith(".sql");
    }

    private static boolean globMatch(String value, String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); ++i) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return value.toLowerCase(Locale.ROOT).matches(regex.toString());
    }

    private void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.walk(path)) {
                List<Path> items = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path item : items) {
                    Files.deleteIfExists(item);
                }
            }
        } else {
            Files.delete(path);
        }
    }

    private Path resolve(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("~")) {
            value = System.getProperty("user.home", "");
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", "") + value.substring(1);
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private void guard(Path path) {
        String text = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (text.contains("/windows/system32") || text.contains("/windows/syswow64")
                || text.contains("/program files/windowsapps")
                || text.contains("/system volume information")
                || text.contains("/$recycle.bin")) {
            throw new IllegalArgumentException("Refusing to modify a protected system path: " + path);
        }
        if (path.toAbsolutePath().normalize().equals(root)) {
            throw new IllegalArgumentException("Refusing to modify the project root itself");
        }
    }

    private Path trashRoot() {
        return root.resolve("workspace").resolve(".trash").resolve("fs");
    }

    private Map<String, Object> denied(String action) {
        audit(action, "", "denied");
        return Json.map("ok", false, "permission_required", true,
                "error", "Whole-disk file access is disabled. Enable it in "
                        + "Settings → Full filesystem access, then try again.");
    }

    private synchronized void audit(String action, String target, String result) {
        try {
            Files.createDirectories(auditFile.getParent());
            Map<String, Object> line = Json.map(
                    "at", Instant.now().toString(),
                    "action", action,
                    "target", target,
                    "result", result);
            Files.writeString(auditFile, Json.write(line) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> readDoc() throws IOException {
        if (!Files.isRegularFile(configFile)) {
            return new LinkedHashMap<>();
        }
        return Json.asObject(Json.parse(Files.readString(configFile, StandardCharsets.UTF_8)));
    }

    private static List<Path> driveRoots() {
        List<Path> out = new ArrayList<>();
        for (Path path : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
            if (Files.isDirectory(path)) {
                out.add(path);
            }
        }
        return out;
    }

    private static String sha256(Path file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) {
                out.append(String.format("%02x", value));
            }
            return out.toString();
        } catch (Exception ex) {
            return "";
        }
    }
}