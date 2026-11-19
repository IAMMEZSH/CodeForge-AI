package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, persistent, full-text index over everything the agent reads online.
 * It gives CodeForge a real large-corpus search: crawled and fetched pages are
 * stored once and stay searchable offline through BM25 ranking, without any
 * external search service or model.
 */
public final class ResearchIndex {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_]{2,}");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int MAX_DOC_CHARS = 400_000;
    private static final int MAX_INDEXED_DOCS = 200_000;

    private final Path directory;
    private final Path corpusFile;
    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, List<int[]>> postings = new HashMap<>();
    private final Map<String, Integer> docByUrl = new HashMap<>();
    private long totalLength;
    private int loadErrors;

    public ResearchIndex(Path root) {
        this.directory = root.resolve("workspace").resolve("research");
        this.corpusFile = directory.resolve("corpus.jsonl");
        load();
    }

    public synchronized Map<String, Object> add(String title, String url, String text,
                                                String source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        String body = text == null ? "" : text.trim();
        if (body.isBlank()) {
            out.put("error", "empty document");
            return out;
        }
        if (body.length() > MAX_DOC_CHARS) {
            body = body.substring(0, MAX_DOC_CHARS);
        }
        String key = urlKey(url);
        Integer existing = key.isBlank() ? null : docByUrl.get(key);
        if (existing != null) {
            out.put("ok", true);
            out.put("doc_id", docs.get(existing).id);
            out.put("duplicate", true);
            out.put("docs", docs.size());
            return out;
        }
        if (docs.size() >= MAX_INDEXED_DOCS) {
            out.put("error", "index is full (" + MAX_INDEXED_DOCS + " documents)");
            return out;
        }
        Doc doc = new Doc(docs.size(), nextId(), trim(title, 300), url == null ? "" : url,
                source == null ? "" : source, body, Instant.now().toString());
        index(doc);
        docs.add(doc);
        if (!key.isBlank()) {
            docByUrl.put(key, doc.index);
        }
        persist(doc);
        out.put("ok", true);
        out.put("duplicate", false);
        out.put("doc_id", doc.id);
        out.put("chars", body.length());
        out.put("docs", docs.size());
        return out;
    }

    public synchronized Map<String, Object> search(String query, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("query", query == null ? "" : query);
        List<String> tokens = queryTokens(query);
        List<Map<String, Object>> hits = new ArrayList<>();
        if (!tokens.isEmpty() && !docs.isEmpty()) {
            Map<Integer, Double> scores = new HashMap<>();
            double avg = docs.isEmpty() ? 1 : Math.max(1.0, (double) totalLength / docs.size());
            int count = docs.size();
            for (String token : tokens) {
                List<int[]> list = postings.get(token);
                if (list == null) {
                    continue;
                }
                double idf = Math.log(1 + (count - list.size() + 0.5) / (list.size() + 0.5));
                for (int[] posting : list) {
                    Doc doc = docs.get(posting[0]);
                    double tf = posting[1];
                    double norm = tf * (K1 + 1)
                            / (tf + K1 * (1 - B + B * doc.length / avg));
                    scores.merge(posting[0], idf * norm, Double::sum);
                }
            }
            List<Map.Entry<Integer, Double>> ranked = new ArrayList<>(scores.entrySet());
            ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            for (Map.Entry<Integer, Double> entry : ranked) {
                if (hits.size() >= limit) {
                    break;
                }
                Doc doc = docs.get(entry.getKey());
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("doc_id", doc.id);
                hit.put("title", doc.title);
                hit.put("url", doc.url);
                hit.put("source", doc.source);
                hit.put("score", Math.round(entry.getValue() * 1000.0) / 1000.0);
                hit.put("chars", doc.text.length());
                hit.put("added_at", doc.addedAt);
                hit.put("snippet", snippet(doc.text, tokens, 320));
                hits.add(hit);
            }
        }
        out.put("count", hits.size());
        out.put("results", hits);
        out.put("stats", stats());
        return out;
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docs", docs.size());
        out.put("terms", postings.size());
        out.put("chars", totalLength);
        out.put("file", corpusFile.toString());
        try {
            out.put("file_bytes", Files.exists(corpusFile) ? Files.size(corpusFile) : 0L);
        } catch (IOException ex) {
            out.put("file_bytes", 0L);
        }
        out.put("load_errors", loadErrors);
        return out;
    }

    /** Extract the most query-relevant passages from a page, in reading order. */
    public static List<String> passages(String text, String query, int maxPassages,
                                        int maxChars) {
        List<String> tokens = queryTokens(query);
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank() || maxPassages <= 0) {
            return out;
        }
        List<String> blocks = new ArrayList<>();
        for (String block : text.split("\\n+")) {
            String clean = block.trim();
            if (clean.isEmpty()) {
                continue;
            }
            if (clean.length() > 700) {
                for (String sentence : clean.split("(?<=[。！？!?;；.])\\s+")) {
                    if (!sentence.isBlank()) {
                        blocks.add(sentence.trim());
                    }
                }
            } else {
                blocks.add(clean);
            }
        }
        Map<String, Double> scores = new HashMap<>();
        for (String block : blocks) {
            if (block.length() < 20) {
                continue;
            }
            String lower = block.toLowerCase(Locale.ROOT);
            double score = 0;
            for (String token : tokens) {
                int hits = occurrences(lower, token, 4);
                score += hits * (token.length() >= 4 ? 2.0 : 1.0);
            }
            if (score > 0) {
                scores.put(block, score);
            }
        }
        List<String> picked;
        if (scores.isEmpty()) {
            picked = new ArrayList<>(blocks.subList(0, Math.min(blocks.size(), maxPassages)));
        } else {
            List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
            ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            Set<String> chosen = new LinkedHashSet<>();
            for (Map.Entry<String, Double> entry : ranked) {
                if (chosen.size() >= maxPassages) {
                    break;
                }
                chosen.add(entry.getKey());
            }
            picked = new ArrayList<>();
            for (String block : blocks) {
                if (chosen.contains(block)) {
                    picked.add(block);
                }
            }
        }
        int budget = Math.max(200, maxChars);
        int used = 0;
        for (String block : picked) {
            if (out.size() >= maxPassages || used >= budget) {
                break;
            }
            String piece = MemoryStore.trim(block, Math.min(600, budget - used));
            if (piece.isBlank()) {
                continue;
            }
            out.add(piece);
            used += piece.length();
        }
        return out;
    }

    public static List<String> queryTokens(String query) {
        return MemoryStore.tokens(query);
    }

    private void index(Doc doc) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String source = doc.title + "\n" + doc.title + "\n" + doc.text;
        Matcher matcher = TOKEN.matcher(source);
        int length = 0;
        while (matcher.find()) {
            String piece = matcher.group();
            if (HAN.matcher(piece).find()) {
                if (piece.length() == 1) {
                    counts.merge(piece, 1, Integer::sum);
                    ++length;
                } else {
                    for (int i = 0; i + 1 < piece.length(); ++i) {
                        counts.merge(piece.substring(i, i + 2), 1, Integer::sum);
                        ++length;
                    }
                }
            } else {
                String word = piece.toLowerCase(Locale.ROOT);
                counts.merge(word, 1, Integer::sum);
                ++length;
            }
        }
        doc.length = Math.max(1, length);
        totalLength += doc.length;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            postings.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .add(new int[]{doc.index, entry.getValue()});
        }
    }

    private void load() {
        if (!Files.exists(corpusFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(corpusFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || docs.size() >= MAX_INDEXED_DOCS) {
                    continue;
                }
                try {
                    Map<String, Object> doc = Json.asObject(Json.parse(line));
                    String text = Json.str(doc, "text", "");
                    if (text.isBlank()) {
                        continue;
                    }
                    Doc record = new Doc(docs.size(), Json.str(doc, "id", ""),
                            Json.str(doc, "title", ""), Json.str(doc, "url", ""),
                            Json.str(doc, "source", ""), text,
                            Json.str(doc, "added_at", ""));
                    index(record);
                    docs.add(record);
                    String key = urlKey(record.url);
                    if (!key.isBlank()) {
                        docByUrl.put(key, record.index);
                    }
                } catch (RuntimeException ex) {
                    ++loadErrors;
                }
            }
        } catch (IOException ex) {
            ++loadErrors;
        }
    }

    private void persist(Doc doc) {
        try {
            Files.createDirectories(directory);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", doc.id);
            record.put("title", doc.title);
            record.put("url", doc.url);
            record.put("source", doc.source);
            record.put("text", doc.text);
            record.put("added_at", doc.addedAt);
            Files.writeString(corpusFile, Json.write(record) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private String nextId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toString(docs.size() + 1, 36);
    }

    private static String snippet(String text, List<String> tokens, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String token : tokens) {
            int at = lower.indexOf(token);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        int start = best < 0 ? 0 : Math.max(0, best - max / 3);
        String piece = text.substring(start,
                Math.min(text.length(), start + max));
        return MemoryStore.trim(piece.replaceAll("\\s+", " ").trim(), max);
    }

    private static int occurrences(String haystack, String needle, int cap) {
        int count = 0;
        int from = 0;
        while (count < cap) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                break;
            }
            ++count;
            from = at + needle.length();
        }
        return count;
    }

    private static String urlKey(String url) {
        String key = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "";
        }
        int fragment = key.indexOf('#');
        if (fragment >= 0) {
            key = key.substring(0, fragment);
        }
        if (key.startsWith("https://")) {
            key = key.substring(8);
        } else if (key.startsWith("http://")) {
            key = key.substring(7);
        }
        if (key.startsWith("www.")) {
            key = key.substring(4);
        }
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    private static String trim(String text, int max) {
        return MemoryStore.trim(text == null ? "" : text, max);
    }

    private static final class Doc {
        final int index;
        final String id;
        final String title;
        final String url;
        final String source;
        final String text;
        final String addedAt;
        int length = 1;

        Doc(int index, String id, String title, String url, String source,
            String text, String addedAt) {
            this.index = index;
            this.id = id;
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.source = source == null ? "" : source;
            this.text = text;
            this.addedAt = addedAt == null ? "" : addedAt;
        }
    }
}