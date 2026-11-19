package codeforge.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the public release index so the console can tell an installed build
 * that a newer version exists. Only the version tag and release page are
 * fetched; no conversation, keys or usage data leave the host.
 */
public final class UpdateChecker {
    private static final String GITHUB_LATEST =
            "https://api.github.com/repos/IAMMEZSH/CodeForge-AI/releases/latest";
    private static final String GITCODE_LIST =
            "https://api.gitcode.com/api/v5/repos/gcw_So3EkhfR/CodeForge-AI/releases?per_page=10";
    private static final String GITHUB_TAG =
            "https://github.com/IAMMEZSH/CodeForge-AI/releases/tag/";
    private static final String GITCODE_TAG =
            "https://gitcode.com/gcw_So3EkhfR/CodeForge-AI/releases/tag/";
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private final Path configFile;
    private final HttpClient http;
    private Map<String, Object> cached;
    private long cachedAt;

    public UpdateChecker(Path root) {
        this.configFile = root.resolve("config.json");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public synchronized Map<String, Object> check(boolean force) {
        if (!enabled()) {
            return Json.map("ok", true, "enabled", false,
                    "current", ApiHandler.VERSION, "update_available", false);
        }
        long now = System.currentTimeMillis();
        if (!force && cached != null && now - cachedAt < TTL_MILLIS) {
            return cached;
        }
        boolean preferGitcode = Locale.getDefault().getLanguage()
                .toLowerCase(Locale.ROOT).startsWith("zh");
        String first = preferGitcode ? "GitCode" : "GitHub";
        String second = preferGitcode ? "GitHub" : "GitCode";
        Release release = null;
        String error = "";
        try {
            release = preferGitcode ? gitcode() : github();
        } catch (Exception ex) {
            error = first + ": " + describe(ex);
        }
        if (release == null) {
            try {
                release = preferGitcode ? github() : gitcode();
            } catch (Exception ex) {
                error += (error.isEmpty() ? "" : "; ") + second + ": " + describe(ex);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", release != null);
        out.put("enabled", true);
        out.put("current", ApiHandler.VERSION);
        if (release == null) {
            out.put("update_available", false);
            out.put("error", "Cannot reach the public release index (" + error + ")");
        } else {
            out.put("latest", release.version);
            out.put("update_available", compare(release.version, ApiHandler.VERSION) > 0);
            out.put("source", release.source);
            out.put("name", release.name);
            out.put("published_at", release.publishedAt);
            out.put("url", release.url);
            out.put("checked_at", Instant.now().toString());
        }
        cached = out;
        cachedAt = now;
        return out;
    }

    private boolean enabled() {
        try {
            if (!Files.isRegularFile(configFile)) return true;
            Map<String, Object> doc = Json.asObject(Json.parse(
                    Files.readString(configFile, StandardCharsets.UTF_8)));
            return Json.bool(Json.asObject(doc.get("updates")), "enabled", true);
        } catch (Exception ex) {
            return true;
        }
    }

    private Release github() throws Exception {
        HttpResponse<String> response = send(GITHUB_LATEST);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        Map<String, Object> doc = Json.asObject(Json.parse(response.body()));
        String tag = Json.str(doc, "tag_name", "").trim();
        if (tag.isEmpty()) throw new IllegalStateException("missing tag_name");
        String url = Json.str(doc, "html_url", "");
        if (url.isBlank()) url = GITHUB_TAG + tag;
        return new Release(cleanVersion(tag), Json.str(doc, "name", tag),
                Json.str(doc, "published_at", ""), url, "github");
    }

    private Release gitcode() throws Exception {
        HttpResponse<String> response = send(GITCODE_LIST);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        List<Object> releases = Json.asList(Json.parse(response.body()));
        Release best = null;
        for (Object raw : releases) {
            Map<String, Object> doc = Json.asObject(raw);
            if (Json.bool(doc, "prerelease", false)) continue;
            String tag = Json.str(doc, "tag_name", "").trim();
            if (tag.isEmpty()) continue;
            String version = cleanVersion(tag);
            if (version.isEmpty()) continue;
            if (best == null || compare(version, best.version) > 0) {
                best = new Release(version, Json.str(doc, "name", tag),
                        Json.str(doc, "created_at", ""),
                        GITCODE_TAG + tag, "gitcode");
            }
        }
        if (best == null) throw new IllegalStateException("no stable release");
        return best;
    }

    private HttpResponse<String> send(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("Accept", "application/json")
                .header("User-Agent", "CodeForge-AI/" + ApiHandler.VERSION)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName() : message;
    }

    static String cleanVersion(String tag) {
        String value = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("v")) value = value.substring(1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch) || ch == '.') out.append(ch);
            else break;
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '.') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    static int compare(String left, String right) {
        String[] a = cleanVersion(left).split("\\.");
        String[] b = cleanVersion(right).split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static final class Release {
        final String version;
        final String name;
        final String publishedAt;
        final String url;
        final String source;

        Release(String version, String name, String publishedAt, String url, String source) {
            this.version = version;
            this.name = name;
            this.publishedAt = publishedAt;
            this.url = url;
            this.source = source;
        }
    }
}