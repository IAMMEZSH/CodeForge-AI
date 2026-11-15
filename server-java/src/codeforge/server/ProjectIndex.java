package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local workspace index. File paths and excerpts are stored under memory/ so
 * the offline model can answer project questions without any network access.
 */
public final class ProjectIndex {
    private static final Set<String> EXTENSIONS = Set.of(
            "md", "markdown", "txt", "java", "kt", "kts", "swift", "py", "c", "h",
            "hpp", "cpp", "cc", "cs", "js", "mjs", "ts", "tsx", "jsx", "html", "htm",
            "css", "json", "yaml", "yml", "toml", "gradle", "sh", "bat", "ps1", "xml",
            "properties", "rs", "go", "rb", "php", "sql", "ini", "cfg", "conf", "cmake");
    private static final Set<String> SKIP_FILES = Set.of(
            "config.json", "installed.json", "runtime.json", ".env", "credentials.json",
            "secrets.json", "id_rsa", "id_ed25519", "memories.jsonl", "compliance.log");
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "models", "release", "build", "memory", "logs", "workspace",
            "node_modules", ".gradle", ".idea", ".vscode", "dist", "downloads");
    private static final int MAX_FILES = 1500;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_TEXT = 4000;
    private static final int MAX_CONTEXT_CHARS = 2400;

    public static final class Doc {
        public String path = "";
        public String text = "";
        public String updatedAt = "";
        public double score;
    }

    private final AppSettings settings;
    private final Path root;
    private final Path dir;
    private final Path file;
    private final List<Doc> docs = new ArrayList<>();
    private boolean loaded;
    private String updatedAt = "";
    private long totalBytes;

    public ProjectIndex(AppSettings settings) {
        this.settings = settings;
        this.root = settings.root();
        this.dir = settings.stateDir();
        this.file = dir.resolve("project-index.jsonl");
    }

    private synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> map = Json.asObject(Json.parse(line));
                    Doc doc = new Doc();
                    doc.path = Json.str(map, "path", "");
                    doc.text = Json.str(map, "text", "");
                    doc.updatedAt = Json.str(map, "updated_at", "");
                    if (!doc.path.isBlank()) {
                        docs.add(doc);
                        totalBytes += doc.text.length();
                        if (doc.updatedAt.compareTo(updatedAt) > 0) {
                            updatedAt = doc.updatedAt;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized Map<String, Object> refresh() {
        List<Doc> scanned = new ArrayList<>();
        long[] bytes = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                    if (!path.equals(root)) {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (SKIP_DIRS.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (scanned.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = path.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (SKIP_FILES.contains(lower) || lower.endsWith(".pem")
                            || lower.endsWith(".key") || lower.endsWith(".pfx")
                            || lower.endsWith(".p12") || lower.endsWith(".min.js")) {
                        return FileVisitResult.CONTINUE;
                    }
                    int dot = lower.lastIndexOf('.');
                    if (dot < 1 || !EXTENSIONS.contains(lower.substring(dot + 1))) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        String text = Files.readString(path, StandardCharsets.UTF_8).trim();
                        if (text.length() < 20) {
                            return FileVisitResult.CONTINUE;
                        }
                        Doc doc = new Doc();
                        doc.path = root.relativize(path).toString().replace('\\', '/');
                        doc.text = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
                        doc.updatedAt = attrs.lastModifiedTime().toInstant().toString();
                        scanned.add(doc);
                        bytes[0] += doc.text.length();
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        scanned.sort(Comparator.comparing(doc -> doc.path));
        docs.clear();
        docs.addAll(scanned);
        totalBytes = bytes[0];
        updatedAt = Instant.now().toString();
        loaded = true;
        persist();
        Map<String, Object> out = stats();
        out.put("ok", true);
        out.put("refreshed", true);
        return out;
    }

    private void persist() {
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve("project-index.jsonl.tmp");
            StringBuilder out = new StringBuilder();
            for (Doc doc : docs) {
                out.append(Json.write(Json.map(
                        "path", doc.path,
                        "text", doc.text,
                        "updated_at", doc.updatedAt))).append('\n');
            }
            Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized Map<String, Object> stats() {
        load();
        return Json.map(
                "ok", true,
                "files", docs.size(),
                "bytes", totalBytes,
                "updated_at", updatedAt,
                "location", "memory/project-index.jsonl",
                "enabled", settings.memoryEnabled());
    }

    public synchronized List<Doc> search(String query, int limit) {
        load();
        int want = Math.max(0, Math.min(10, limit));
        if (want == 0 || docs.isEmpty()) {
            return List.of();
        }
        List<String> tokens = MemoryStore.tokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> df = new HashMap<>();
        List<Map<String, Integer>> counts = new ArrayList<>(docs.size());
        for (Doc doc : docs) {
            String haystack = (doc.path + "\n" + doc.text).toLowerCase(Locale.ROOT);
            Map<String, Integer> local = new HashMap<>();
            for (String token : tokens) {
                int count = countOccurrences(haystack, token);
                if (count > 0) {
                    local.put(token, count);
                    df.merge(token, 1, Integer::sum);
                }
            }
            counts.add(local);
        }
        int total = docs.size();
        List<Doc> ranked = new ArrayList<>();
        for (int i = 0; i < docs.size(); ++i) {
            Map<String, Integer> local = counts.get(i);
            if (local.isEmpty()) {
                continue;
            }
            Doc doc = docs.get(i);
            double score = 0;
            for (Map.Entry<String, Integer> hit : local.entrySet()) {
                double idf = Math.log((total + 1.0) / (df.getOrDefault(hit.getKey(), 1) + 1.0)) + 1.0;
                score += Math.min(5, hit.getValue()) * idf;
                if (doc.path.toLowerCase(Locale.ROOT).contains(hit.getKey())) {
                    score += 2.0;
                }
            }
            if (score < 1.0) {
                continue;
            }
            Doc copy = new Doc();
            copy.path = doc.path;
            copy.text = excerpt(doc.text, tokens);
            copy.updatedAt = doc.updatedAt;
            copy.score = score;
            ranked.add(copy);
        }
        ranked.sort(Comparator.comparingDouble((Doc doc) -> doc.score).reversed());
        return ranked.size() > want ? new ArrayList<>(ranked.subList(0, want)) : ranked;
    }

    private static String excerpt(String text, List<String> tokens) {
        String lower = text.toLowerCase(Locale.ROOT);
        int index = -1;
        for (String token : tokens) {
            int found = lower.indexOf(token);
            if (found >= 0 && (index < 0 || found < index)) {
                index = found;
            }
        }
        if (index < 0) {
            return text.length() > 700 ? text.substring(0, 700) + "…" : text;
        }
        int start = Math.max(0, index - 220);
        int end = Math.min(text.length(), index + 520);
        String piece = text.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + piece + (end < text.length() ? "…" : "");
    }

    public synchronized Map<String, Object> query(String text, int limit) {
        List<Object> hits = new ArrayList<>();
        for (Doc doc : search(text, limit)) {
            hits.add(Json.map(
                    "path", doc.path,
                    "excerpt", doc.text,
                    "updated_at", doc.updatedAt,
                    "score", Math.round(doc.score * 100.0) / 100.0));
        }
        Map<String, Object> out = new LinkedHashMap<>(stats());
        out.put("query", text == null ? "" : text);
        out.put("hits", hits);
        return out;
    }

    public synchronized String contextFor(List<Doc> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("WORKSPACE CONTEXT (local project files, cite paths, never invent content):\n");
        for (Doc doc : hits) {
            String block = "- " + doc.path + ": " + doc.text + "\n";
            if (out.length() + block.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            out.append(block);
        }
        return out.toString().trim();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while (count < 5) {
            int found = haystack.indexOf(needle, index);
            if (found < 0) {
                break;
            }
            ++count;
            index = found + needle.length();
        }
        return count;
    }

    public void refreshAsync() {
        Thread thread = new Thread(() -> {
            try {
                refresh();
            } catch (Exception ignored) {
            }
        }, "project-index");
        thread.setDaemon(true);
        thread.start();
    }
}
