package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only local memory. Chat turns and web research are stored as JSONL,
 * scored with token overlap plus recency, and replayed when the model runs
 * offline, so online learning survives disconnection.
 */
public final class MemoryStore {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9_]{2,}");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "you", "are",
            "was", "were", "have", "has", "not", "but", "what", "how", "why",
            "when", "where", "which", "who", "will", "can", "could", "should",
            "into", "about", "your", "our", "their", "its", "的", "了", "是",
            "在", "和", "与", "有", "我", "你", "他", "她", "它", "这", "那",
            "什么", "怎么", "如何", "可以", "一个", "没有", "不是");
    private static final int MAX_TEXT = 4000;
    private static final int MAX_CONTEXT_CHARS = 2600;

    public static final class Entry {
        public String id = "";
        public String ts = "";
        public String kind = "fact";
        public String text = "";
        public String source = "";
        public String hash = "";
        public double score;
    }

    private final AppSettings settings;
    private final Path dir;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> hashes = new HashSet<>();
    private boolean loaded;

    public MemoryStore(AppSettings settings) {
        this.settings = settings;
        this.dir = settings.stateDir();
        this.file = dir.resolve("memories.jsonl");
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
                    Entry entry = fromMap(map);
                    if (!entry.text.isBlank() && hashes.add(entry.hash)) {
                        entries.add(entry);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        prune(settings.maxEntries());
    }

    private static Entry fromMap(Map<String, Object> map) {
        Entry entry = new Entry();
        entry.id = Json.str(map, "id", "");
        entry.ts = Json.str(map, "ts", "");
        entry.kind = Json.str(map, "kind", "fact");
        entry.text = Json.str(map, "text", "");
        entry.source = Json.str(map, "source", "");
        entry.hash = Json.str(map, "hash", "");
        return entry;
    }

    private static Map<String, Object> toMap(Entry entry) {
        return Json.map(
                "id", entry.id,
                "ts", entry.ts,
                "kind", entry.kind,
                "text", entry.text,
                "source", entry.source,
                "hash", entry.hash);
    }

    public synchronized Map<String, Object> add(String text, String kind, String source) {
        load();
        String clean = cleanText(text);
        if (clean.length() < 4) {
            return Json.map("ok", false, "error", "Memory text is too short");
        }
        String hash = digest(clean);
        if (hashes.contains(hash)) {
            return Json.map("ok", true, "duplicate", true, "entries", entries.size());
        }
        Entry entry = new Entry();
        entry.id = UUID.randomUUID().toString().substring(0, 12);
        entry.ts = Instant.now().toString();
        entry.kind = normalizeKind(kind);
        entry.text = clean;
        entry.source = source == null ? "" : source.trim();
        entry.hash = hash;
        entries.add(entry);
        hashes.add(hash);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, Json.write(toMap(entry)) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            return Json.map("ok", false, "error", "Cannot write memory store: " + ex.getMessage());
        }
        prune(settings.maxEntries());
        return Json.map("ok", true, "duplicate", false, "id", entry.id,
                "entries", entries.size());
    }

    public synchronized Map<String, Object> stats() {
        load();
        long bytes = 0;
        if (Files.isRegularFile(file)) {
            try {
                bytes = Files.size(file);
            } catch (IOException ignored) {
            }
        }
        String last = entries.isEmpty() ? "" : entries.get(entries.size() - 1).ts;
        return Json.map(
                "ok", true,
                "enabled", settings.memoryEnabled(),
                "auto_capture", settings.autoCapture(),
                "entries", entries.size(),
                "bytes", bytes,
                "last_updated", last,
                "max_entries", settings.maxEntries(),
                "retrieval_limit", settings.retrievalLimit());
    }

    public synchronized List<Map<String, Object>> recent(int limit) {
        load();
        int count = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < count; --i) {
            out.add(resultMap(entries.get(i), 0));
        }
        return out;
    }

    public synchronized List<Entry> search(String query, int limit) {
        load();
        int want = Math.max(0, Math.min(20, limit));
        if (want == 0 || entries.isEmpty()) {
            return List.of();
        }
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            List<Entry> fallback = new ArrayList<>();
            for (int i = entries.size() - 1; i >= 0 && fallback.size() < want; --i) {
                fallback.add(entries.get(i));
            }
            return fallback;
        }
        int total = entries.size();
        Map<String, Integer> df = new HashMap<>();
        Map<String, Integer> tf = new HashMap<>();
        List<Map<String, Integer>> counts = new ArrayList<>(total);
        for (Entry entry : entries) {
            String haystack = entry.text.toLowerCase(Locale.ROOT);
            Map<String, Integer> local = new HashMap<>();
            for (String token : tokens) {
                int count = countOccurrences(haystack, token);
                if (count > 0) {
                    local.put(token, count);
                    df.merge(token, 1, Integer::sum);
                    tf.merge(token, count, Integer::sum);
                }
            }
            counts.add(local);
        }
        String phrase = query == null ? "" : normalize(query);
        List<Entry> ranked = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < entries.size(); ++i) {
            Entry entry = entries.get(i);
            Map<String, Integer> local = counts.get(i);
            if (local.isEmpty()) {
                continue;
            }
            double score = 0;
            for (Map.Entry<String, Integer> hit : local.entrySet()) {
                double idf = Math.log((total + 1.0) / (df.getOrDefault(hit.getKey(), 1) + 1.0)) + 1.0;
                score += Math.min(5, hit.getValue()) * idf;
            }
            String haystack = normalize(entry.text);
            if (!phrase.isEmpty() && haystack.contains(phrase)) {
                score += 2.5;
            }
            score *= kindWeight(entry.kind);
            score += recencyBoost(entry.ts, now);
            if (score < 0.5) {
                continue;
            }
            Entry copy = new Entry();
            copy.id = entry.id;
            copy.ts = entry.ts;
            copy.kind = entry.kind;
            copy.text = entry.text;
            copy.source = entry.source;
            copy.hash = entry.hash;
            copy.score = score;
            ranked.add(copy);
        }
        ranked.sort(Comparator.comparingDouble((Entry item) -> item.score).reversed());
        return ranked.size() > want ? new ArrayList<>(ranked.subList(0, want)) : ranked;
    }

    public synchronized Map<String, Object> query(String text, int limit) {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Entry entry : search(text, limit)) {
            hits.add(resultMap(entry, entry.score));
        }
        Map<String, Object> result = new LinkedHashMap<>(stats());
        result.put("query", text == null ? "" : text);
        result.put("hits", hits);
        return result;
    }

    private static Map<String, Object> resultMap(Entry entry, double score) {
        return Json.map(
                "id", entry.id,
                "ts", entry.ts,
                "kind", entry.kind,
                "text", entry.text,
                "source", entry.source,
                "score", Math.round(score * 100.0) / 100.0);
    }

    public synchronized Map<String, Object> delete(String id) {
        load();
        boolean removed = entries.removeIf(entry -> entry.id.equals(id));
        if (!removed) {
            return Json.map("ok", false, "error", "Unknown memory id: " + id);
        }
        rebuild();
        return Json.map("ok", true, "entries", entries.size());
    }

    public synchronized Map<String, Object> clear() {
        load();
        entries.clear();
        hashes.clear();
        try {
            Files.createDirectories(dir);
            Files.writeString(file, "", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return Json.map("ok", false, "error", "Cannot clear memory: " + ex.getMessage());
        }
        return Json.map("ok", true, "entries", 0);
    }

    public synchronized String contextFor(List<Entry> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("LOCAL MEMORY (persistent notes from this machine; verify anything stale):\n");
        for (Entry entry : hits) {
            String line = "- [" + entry.kind + " · " + shortStamp(entry.ts) + "] "
                    + entry.text;
            if (!entry.source.isBlank()) {
                line += " (" + entry.source + ")";
            }
            if (out.length() + line.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }

    public synchronized void rememberExchange(String user, String assistant, String model) {
        if (!settings.memoryEnabled() || !settings.autoCapture()) {
            return;
        }
        String note = "User: " + trim(user, 700) + "\nAssistant: " + trim(assistant, 1100);
        if (!model.isBlank()) {
            note += "\nModel: " + model;
        }
        add(note, "chat", "session");
    }

    private void prune(int max) {
        if (entries.size() <= max) {
            return;
        }
        int excess = entries.size() - max;
        List<Entry> chat = new ArrayList<>();
        for (Entry entry : entries) {
            if ("chat".equals(entry.kind)) {
                chat.add(entry);
            }
        }
        Set<String> drop = new HashSet<>();
        for (Entry entry : chat) {
            if (drop.size() >= excess) {
                break;
            }
            drop.add(entry.id);
        }
        for (Entry entry : entries) {
            if (drop.size() >= excess) {
                break;
            }
            if (!"chat".equals(entry.kind)) {
                drop.add(entry.id);
            }
        }
        entries.removeIf(entry -> drop.contains(entry.id));
        hashes.clear();
        for (Entry entry : entries) {
            hashes.add(entry.hash);
        }
        rebuild();
    }

    private void rebuild() {
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve("memories.jsonl.tmp");
            StringBuilder out = new StringBuilder();
            for (Entry entry : entries) {
                out.append(Json.write(toMap(entry))).append('\n');
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

    private static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace('\u0000', ' ').replace("\r\n", "\n").trim();
        return trim(text, MAX_TEXT);
    }

    public static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "fact", "preference", "web", "chat", "lesson", "note" -> value;
            default -> "note";
        };
    }

    private static double kindWeight(String kind) {
        return switch (kind) {
            case "fact", "lesson" -> 1.3;
            case "preference" -> 1.2;
            case "web" -> 1.1;
            case "chat" -> 0.9;
            default -> 1.0;
        };
    }

    private static double recencyBoost(String stamp, Instant now) {
        try {
            Instant when = Instant.parse(stamp);
            double days = Math.max(0, Duration.between(when, now).toHours() / 24.0);
            return 0.4 * Math.exp(-days / 90.0);
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String shortStamp(String stamp) {
        return stamp.length() >= 10 ? stamp.substring(0, 10) : stamp;
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        Set<String> seen = new HashSet<>();
        while (matcher.find() && out.size() < 24) {
            String piece = matcher.group();
            if (isHan(piece)) {
                if (piece.length() == 1) {
                    addToken(out, seen, piece);
                } else {
                    for (int i = 0; i + 1 < piece.length() && out.size() < 24; ++i) {
                        addToken(out, seen, piece.substring(i, i + 2));
                    }
                }
            } else if (piece.length() >= 2 && !STOPWORDS.contains(piece)) {
                addToken(out, seen, piece);
            }
        }
        return out;
    }

    private static boolean isHan(String value) {
        return !value.isEmpty()
                && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    private static void addToken(List<String> out, Set<String> seen, String token) {
        if (STOPWORDS.contains(token)) {
            return;
        }
        if (seen.add(token)) {
            out.add(token);
        }
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

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    private static String digest(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(normalize(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; ++i) {
                out.append(String.format("%02x", hash[i]));
            }
            return out.toString();
        } catch (Exception ex) {
            return Integer.toHexString(normalize(text).hashCode());
        }
    }
}
