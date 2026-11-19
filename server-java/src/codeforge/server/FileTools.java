package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File tools for the local agent: read, write, edit and list project files.
 * Large projects need reliable file I/O without shell quoting problems.
 */
public final class FileTools {
    private static final int MAX_WRITE_CHARS = 4_000_000;
    private static final int MAX_READ_CHARS = 200_000;
    private static final int MAX_ENTRIES = 800;
    private static final List<String> SKIP_DIRS = List.of(
            ".git", "node_modules", ".gradle", "target", "build", "dist",
            "__pycache__", ".venv", "venv", ".idea", ".vscode", "bin", "obj");

    private final Path root;

    public FileTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Map<String, Object> run(String kind, Map<String, Object> tool) {
        long started = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            switch (kind) {
                case "write" -> write(tool, out);
                case "read" -> read(tool, out);
                case "list" -> list(tool, out);
                case "edit" -> edit(tool, out);
                default -> {
                    out.put("ok", false);
                    out.put("error", "unknown file operation: " + kind);
                }
            }
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
        out.put("kind", kind);
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    private void write(Map<String, Object> tool, Map<String, Object> out) throws IOException {
        String raw = Json.str(tool, "path", "");
        if (raw.isBlank()) {
            out.put("ok", false);
            out.put("error", "path is required");
            return;
        }
        String content = Json.str(tool, "content", "");
        if (content.length() > MAX_WRITE_CHARS) {
            out.put("ok", false);
            out.put("error", "content too large (" + content.length()
                    + " chars); split it into several smaller files");
            return;
        }
        if (content.indexOf('\0') >= 0) {
            out.put("ok", false);
            out.put("error", "binary content is not supported; write text files only");
            return;
        }
        Path path = resolve(raw);
        guardWrite(path);
        boolean append = Json.bool(tool, "append", false);
        if (Files.isDirectory(path)) {
            out.put("ok", false);
            out.put("error", "path is a directory: " + path);
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        int before = Files.exists(path) && Files.isRegularFile(path)
                ? Files.readAllBytes(path).length : 0;
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
        Files.writeString(path, content, StandardCharsets.UTF_8, options);
        byte[] after = Files.readAllBytes(path);
        out.put("ok", true);
        out.put("path", path.toString());
        out.put("append", append);
        out.put("chars_written", content.length());
        out.put("bytes_before", before);
        out.put("bytes_after", after.length);
        out.put("lines", countLines(after));
    }

    private void read(Map<String, Object> tool, Map<String, Object> out) throws IOException {
        Path path = resolve(Json.str(tool, "path", ""));
        if (!Files.exists(path)) {
            out.put("ok", false);
            out.put("error", "file not found: " + path);
            return;
        }
        if (Files.isDirectory(path)) {
            out.put("ok", false);
            out.put("error", "path is a directory; use codeforge_list: " + path);
            return;
        }
        if (Files.size(path) > 40L * 1024 * 1024) {
            out.put("ok", false);
            out.put("error", "file is larger than 40 MB; use shell tools to inspect it");
            return;
        }
        byte[] bytes = Files.readAllBytes(path);
        int probe = Math.min(bytes.length, 8192);
        for (int i = 0; i < probe; ++i) {
            if (bytes[i] == 0) {
                out.put("ok", false);
                out.put("error", "binary file cannot be read as text (" + bytes.length
                        + " bytes)");
                return;
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);
        int start = Math.max(1, Math.min(lines.length + 1,
                Json.integer(tool, "start", 1)));
        int limit = Math.max(1, Math.min(5000, Json.integer(tool, "lines", 500)));
        int end = Math.min(lines.length, start - 1 + limit);
        StringBuilder body = new StringBuilder();
        for (int i = start; i <= end; ++i) {
            body.append(i).append('\t').append(lines[i - 1]).append('\n');
            if (body.length() > MAX_READ_CHARS) {
                break;
            }
        }
        out.put("ok", true);
        out.put("path", path.toString());
        out.put("total_lines", lines.length);
        out.put("start", start);
        out.put("end", end);
        out.put("truncated", end < lines.length || body.length() > MAX_READ_CHARS);
        out.put("content", capText(body.toString(), MAX_READ_CHARS));
    }

    private void list(Map<String, Object> tool, Map<String, Object> out) throws IOException {
        String raw = Json.str(tool, "path", "");
        Path path = raw.isBlank() ? root : resolve(raw);
        if (!Files.exists(path)) {
            out.put("ok", false);
            out.put("error", "path not found: " + path);
            return;
        }
        if (!Files.isDirectory(path)) {
            out.put("ok", false);
            out.put("error", "path is a file; use codeforge_read: " + path);
            return;
        }
        int depth = Math.max(1, Math.min(6, Json.integer(tool, "depth", 2)));
        List<Map<String, Object>> entries = new ArrayList<>();
        collect(path, depth, entries);
        out.put("ok", true);
        out.put("path", path.toString());
        out.put("depth", depth);
        out.put("count", entries.size());
        out.put("truncated", entries.size() >= MAX_ENTRIES);
        out.put("entries", entries);
    }

    private void collect(Path dir, int depth, List<Map<String, Object>> entries)
            throws IOException {
        if (depth <= 0 || entries.size() >= MAX_ENTRIES) {
            return;
        }
        List<Path> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(children::add);
        }
        children.sort((a, b) -> a.getFileName().toString()
                .compareToIgnoreCase(b.getFileName().toString()));
        for (Path child : children) {
            if (entries.size() >= MAX_ENTRIES) {
                return;
            }
            String name = child.getFileName().toString();
            boolean directory = Files.isDirectory(child);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", child.toString());
            item.put("name", name);
            item.put("type", directory ? "dir" : "file");
            if (!directory) {
                try {
                    item.put("bytes", Files.size(child));
                } catch (IOException ignored) {
                }
            }
            entries.add(item);
            if (directory && !SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                collect(child, depth - 1, entries);
            }
        }
    }

    private void edit(Map<String, Object> tool, Map<String, Object> out) throws IOException {
        Path path = resolve(Json.str(tool, "path", ""));
        guardWrite(path);
        if (!Files.isRegularFile(path)) {
            out.put("ok", false);
            out.put("error", "file not found: " + path);
            return;
        }
        String find = Json.str(tool, "find", "");
        String replace = Json.str(tool, "replace", "");
        if (find.isEmpty()) {
            out.put("ok", false);
            out.put("error", "find is required");
            return;
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        boolean all = Json.bool(tool, "all", false);
        int count = countOccurrences(text, find);
        if (count == 0) {
            out.put("ok", false);
            out.put("error", "find text was not found in " + path);
            return;
        }
        if (count > 1 && !all) {
            out.put("ok", false);
            out.put("error", "find text matches " + count
                    + " places; add more context or set \"all\":true");
            return;
        }
        String updated = all ? text.replace(find, replace)
                : text.replaceFirst(java.util.regex.Pattern.quote(find),
                        java.util.regex.Matcher.quoteReplacement(replace));
        if (updated.length() > MAX_WRITE_CHARS) {
            out.put("ok", false);
            out.put("error", "result would be too large; edit the file in smaller steps");
            return;
        }
        Files.writeString(path, updated, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        out.put("ok", true);
        out.put("path", path.toString());
        out.put("replacements", all ? count : 1);
        out.put("lines", updated.split("\\R", -1).length);
    }

    private Path resolve(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        value = value.replace('/', java.io.File.separatorChar);
        String home = System.getProperty("user.home", "");
        if (value.equals("~")) {
            value = home;
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = home + value.substring(1);
        }
        value = expandEnv(value);
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String expandEnv(String value) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c == '%') {
                int end = value.indexOf('%', index + 1);
                if (end > index + 1) {
                    String name = value.substring(index + 1, end);
                    String env = System.getenv(name);
                    if (env != null) {
                        out.append(env);
                        index = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            ++index;
        }
        return out.toString();
    }

    private void guardWrite(Path path) {
        String text = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean win = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        if (win) {
            List<String> denied = List.of(
                    "/windows/", "/program files/", "/program files (x86)/",
                    "/programdata/microsoft/", "/system volume information/",
                    "/$recycle.bin/", "/recovery/");
            for (String marker : denied) {
                if (text.contains(marker)) {
                    throw new IllegalArgumentException(
                            "refusing to modify protected system path: " + path);
                }
            }
            if (path.getParent() == null) {
                throw new IllegalArgumentException(
                        "refusing to write to a drive root; choose a folder");
            }
        } else {
            for (String marker : List.of("/etc/", "/usr/", "/bin/", "/sbin/",
                    "/boot/", "/sys/", "/proc/")) {
                if (text.startsWith(marker)) {
                    throw new IllegalArgumentException(
                            "refusing to modify protected system path: " + path);
                }
            }
        }
    }

    private static int countOccurrences(String text, String find) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(find, index)) >= 0) {
            ++count;
            index += find.length();
        }
        return count;
    }

    private static int countLines(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int lines = 1;
        for (byte b : bytes) {
            if (b == '\n') {
                ++lines;
            }
        }
        return lines;
    }

    private static String capText(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max)
                + "\n... [truncated " + (text.length() - max) + " chars]";
    }
}