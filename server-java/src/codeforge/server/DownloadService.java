package codeforge.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Download center for software, games and arbitrary web files. A direct URL
 * is downloaded with resume, progress, checksum and real byte accounting.
 * When only a name is supplied it searches the web and GitHub releases,
 * inspects pages for downloadable files, and ranks candidates by name,
 * platform, official source and expected size. It refuses torrents and
 * piracy/crack sources, and never executes a downloaded file.
 */
public final class DownloadService {
    private static final Pattern HREF = Pattern.compile(
            "(?is)<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern TITLE = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");
    private static final Set<String> FILE_EXT = Set.of(
            "exe", "msi", "msix", "zip", "7z", "rar", "tar", "gz", "tgz", "bz2",
            "xz", "dmg", "pkg", "apk", "aab", "iso", "img", "deb", "rpm", "appimage",
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "csv", "txt",
            "mp4", "mkv", "mp3", "wav", "png", "jpg", "jpeg", "webp", "gif",
            "html", "htm", "json", "xml", "epub", "mobi", "jar", "bin");
    private static final Set<String> SOFTWARE_EXT = Set.of(
            "exe", "msi", "msix", "zip", "7z", "rar", "dmg", "pkg", "deb", "rpm",
            "appimage", "apk", "aab", "iso", "img", "jar");
    private static final Set<String> DOC_EXT = Set.of(
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "csv", "txt",
            "epub", "mobi", "odt", "ods", "odp");
    private static final Set<String> BAD_WORDS = Set.of(
            "crack", "keygen", "serial", "破解", "注册机", "激活工具", "盗版",
            "绿色破解", "免授权", "warez", "nulled");
    private static final Set<String> BAD_HOSTS = Set.of(
            "thepiratebay.org", "1337x.to", "rarbg.to", "fitgirl-repacks.site",
            "dodi-repacks.site", "igg-games.com", "steamunlocked.net",
            "oceanofgames.com", "apunkagames.com", "crackingpatching.com");
    private static final Set<String> JUNK_HOSTS = Set.of(
            "en.wikipedia.org", "zh.wikipedia.org", "www.youtube.com", "youtube.com",
            "www.facebook.com", "twitter.com", "x.com", "www.reddit.com",
            "zhihu.com", "www.zhihu.com", "baike.baidu.com", "jingyan.baidu.com",
            "www.pinterest.com", "apps.microsoft.com", "play.google.com");
    private static final Set<String> STOP_WORDS = Set.of(
            "official", "download", "free", "windows", "win", "pc", "app",
            "software", "game", "games", "latest", "version", "for", "the",
            "and", "with", "downloads", "installer", "setup", "中文", "官方",
            "下载", "免费", "最新", "版");
    private static final int MAX_CANDIDATES = 40;

    private final Path root;
    private final Path downloads;
    private final OnlineService online;
    private final KeyStore keys;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "cforge-download");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public DownloadService(Path root, OnlineService online, KeyStore keys) {
        this.root = root.toAbsolutePath().normalize();
        this.downloads = this.root.resolve("downloads");
        this.online = online;
        this.keys = keys;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .cookieHandler(new CookieManager())
                .build();
    }

    private HttpClient newDownloadClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public Map<String, Object> start(Map<String, Object> request) {
        String url = Json.str(request, "url", "").trim();
        String query = Json.str(request, "query", "").trim();
        if (url.isEmpty() && query.isEmpty()) {
            return Json.map("ok", false,
                    "error", "Provide url or query. With a query the service searches "
                            + "for the most likely official file.");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Job job = new Job(id, url, query);
        job.targetDir = resolveTarget(Json.str(request, "target_dir", ""));
        job.filename = sanitizeFilename(Json.str(request, "filename", ""));
        job.kind = Json.str(request, "kind", "software").trim().toLowerCase(Locale.ROOT);
        job.expectedBytes = Math.max(0,
                Math.round(Json.decimal(request, "expected_size_mb", 0) * 1024 * 1024));
        job.maxBytes = Math.max(1L * 1024 * 1024,
                (long) (Math.min(204800, Json.decimal(request, "max_mb", 8192)) * 1024 * 1024));
        job.expectedSha256 = Json.str(request, "expected_sha256", "").trim().toLowerCase(Locale.ROOT);
        job.auto = Json.bool(request, "auto", true);
        job.savePage = Json.bool(request, "save_page", false);
        job.platform = Json.str(request, "platform", currentPlatform()).toLowerCase(Locale.ROOT);
        job.overwrite = Json.bool(request, "overwrite", false);
        jobs.put(id, job);
        executor.submit(() -> run(job, request));
        return Json.map("ok", true, "job", view(job),
                "message", url.isEmpty()
                        ? "Resolving the best download for: " + query
                        : "Download started: " + url);
    }

    public Map<String, Object> resolve(Map<String, Object> request) {
        String query = Json.str(request, "query", "").trim();
        String url = Json.str(request, "url", "").trim();
        if (query.isEmpty() && url.isEmpty()) {
            return Json.map("ok", false, "error", "Provide query or url");
        }
        try {
            List<Map<String, Object>> candidates = new ArrayList<>();
            if (!url.isEmpty()) {
                candidates.addAll(candidatesFromUrl(url, query,
                        Json.str(request, "kind", "software"), request));
            }
            if (!query.isEmpty()) {
                candidates.addAll(searchCandidates(query, request,
                        Json.str(request, "kind", "software")));
            }
            candidates = rank(candidates, query, request);
            Map<String, Object> out = Json.map(
                    "ok", true,
                    "query", query,
                    "url", url,
                    "count", candidates.size(),
                    "candidates", candidates,
                    "note", candidates.isEmpty()
                            ? "No downloadable file was found. Try a direct URL or a more exact name."
                            : "Candidates are ranked by official source, filename match, "
                                    + "platform and expected size. Nothing has been downloaded yet.");
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Job job : jobs.values()) {
            items.add(view(job));
        }
        items.sort(Comparator.comparingLong(
                (Map<String, Object> item) -> Json.lon(item, "created_ms", 0)).reversed());
        return Json.map("ok", true, "count", items.size(), "jobs", items,
                "directory", downloads.toString());
    }

    public Map<String, Object> get(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return Json.map("ok", false, "error", "Unknown download job: " + id);
        }
        return Json.map("ok", true, "job", view(job));
    }

    public Map<String, Object> cancel(Map<String, Object> request) {
        String id = Json.str(request, "id", "");
        Job job = jobs.get(id);
        if (job == null) {
            return Json.map("ok", false, "error", "Unknown download job: " + id);
        }
        job.cancel.set(true);
        if (!job.state.startsWith("done") && !"failed".equals(job.state)) {
            job.setState("cancelling");
        }
        return Json.map("ok", true, "job", view(job));
    }

    public Map<String, Object> openFolder(Map<String, Object> request) {
        String id = Json.str(request, "id", "");
        Path target = downloads;
        Job job = jobs.get(id);
        if (job != null && job.savedPath != null) {
            target = job.savedPath.getParent();
        } else {
            String raw = Json.str(request, "path", "");
            if (!raw.isBlank()) {
                target = resolve(raw);
            }
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String> command;
            if (os.contains("win")) {
                command = List.of("explorer.exe", target.toString());
            } else if (os.contains("mac")) {
                command = List.of("open", target.toString());
            } else {
                command = List.of("xdg-open", target.toString());
            }
            new ProcessBuilder(command).start();
            return Json.map("ok", true, "path", target.toString());
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex), "path", target.toString());
        }
    }

    /* ---------------------------------------------------------------- */
    /* job execution                                                     */
    /* ---------------------------------------------------------------- */

    private void run(Job job, Map<String, Object> request) {
        long started = System.currentTimeMillis();
        try {
            Files.createDirectories(job.targetDir);
            job.setState("resolving");
            if (!job.url.isBlank()) {
                job.attemptedUrls.add(job.url);
                download(job);
            } else {
                job.setState("resolving");
                List<Map<String, Object>> candidates = searchCandidates(
                        job.query, request, job.kind);
                candidates = rank(candidates, job.query, request);
                job.candidates = candidates;
                if (candidates.isEmpty()) {
                    job.fail("No downloadable file was found for: " + job.query);
                    return;
                }
                Map<String, Object> best = candidates.get(0);
                double bestScore = Json.decimal(best, "score", 0);
                if (!job.auto || bestScore < 35) {
                    job.setState("needs_choice");
                    job.error = bestScore < 35
                            ? "No candidate is reliable enough. Review the candidates and choose one."
                            : "Choose the file to download.";
                    return;
                }
                job.chosen = best;
                job.url = Json.str(best, "url", "");
                job.candidateIndex = 0;
                job.attemptedUrls.add(job.url);
                download(job);
            }
        } catch (Exception ex) {
            job.fail(message(ex));
        } finally {
            job.durationMs = System.currentTimeMillis() - started;
            job.updatedMs = System.currentTimeMillis();
        }
    }

    private void download(Job job) {
        if (job.cancel.get()) {
            job.setState("cancelled");
            return;
        }
        Path part = null;
        try {
            URI uri = new URI(job.url);
            String provisional = job.filename;
            if (provisional.isBlank()) {
                provisional = filenameFromUrl(uri);
            }
            if (provisional.isBlank()) {
                provisional = "download-" + job.id;
            }
            provisional = sanitizeFilename(provisional);
            Path provisionalTarget = uniqueTarget(
                    job.targetDir.resolve(provisional), job.overwrite);
            part = provisionalTarget.resolveSibling(
                    provisionalTarget.getFileName() + ".part");
            job.lastPartPath = part;
            long existing = Files.isRegularFile(part) ? Files.size(part) : 0;
            job.setState("connecting");
            job.bytes = existing;
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(30))
                    .header("User-Agent", userAgent())
                    .header("Accept", "*/*");
            if (existing > 0) {
                builder.header("Range", "bytes=" + existing + "-");
            }
            HttpResponse<InputStream> response = newDownloadClient().send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            if (code == 416 && existing > 0) {
                Files.deleteIfExists(part);
                existing = 0;
                job.bytes = 0;
                download(job);
                return;
            }
            if (code >= 400) {
                response.body().close();
                retryOrFail(job, "HTTP " + code + " for " + job.url);
                return;
            }
            String contentType = response.headers().firstValue("content-type")
                    .orElse("").toLowerCase(Locale.ROOT);
            boolean html = contentType.contains("text/html")
                    || contentType.contains("application/xhtml");
            if (html && !job.savePage && !"webpage".equals(job.kind)) {
                response.body().close();
                List<Map<String, Object>> candidates = candidatesFromUrl(
                        job.url, job.query, job.kind, new LinkedHashMap<>());
                candidates = rank(candidates, job.query, new LinkedHashMap<>());
                job.candidates = candidates;
                if (!candidates.isEmpty() && (job.auto || candidates.size() == 1)
                        && Json.decimal(candidates.get(0), "score", 0) >= 25) {
                    job.chosen = candidates.get(0);
                    job.url = Json.str(job.chosen, "url", job.url);
                    job.candidateIndex = 0;
                    job.attemptedUrls.add(job.url);
                    download(job);
                } else {
                    job.setState("needs_choice");
                    job.error = "The link is a web page. Choose one of the discovered files.";
                }
                return;
            }
            if (existing > 0 && code != 206) {
                existing = 0;
                job.bytes = 0;
            }
            String filename = job.filename;
            if (filename.isBlank()) {
                filename = filenameFromDisposition(response.headers()
                        .firstValue("content-disposition").orElse(""));
            }
            if (filename.isBlank()) {
                filename = filenameFromUrl(response.uri());
            }
            if (filename.isBlank()) {
                filename = provisional;
            }
            if (extension(filename).isEmpty()) {
                String ext = extensionForType(contentType);
                if (!ext.isEmpty()) {
                    filename += "." + ext;
                }
            }
            filename = sanitizeFilename(filename);
            if (filename.isBlank()) {
                filename = "download-" + job.id;
            }
            Path target = uniqueTarget(job.targetDir.resolve(filename), job.overwrite);
            job.filename = target.getFileName().toString();
            job.savedPath = target;
            job.contentType = contentType;
            long length = response.headers().firstValueAsLong("content-length").orElse(0);
            long total = existing + length;
            if (length <= 0) {
                total = 0;
            }
            if (total > 0 && total > job.maxBytes) {
                response.body().close();
                job.fail("File is " + fmt(total) + ", above the " + fmt(job.maxBytes)
                        + " limit for this job");
                return;
            }
            if (existing == 0) {
                Files.deleteIfExists(part);
            }
            job.totalBytes = total;
            job.setState("downloading");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (existing > 0) {
                try (InputStream old = Files.newInputStream(part)) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = old.read(buffer)) > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            long lastUpdate = System.currentTimeMillis();
            long lastBytes = existing;
            long lastSample = lastUpdate;
            long speed = 0;
            final long[] lastProgress = {System.currentTimeMillis()};
            final boolean[] finished = {false};
            InputStream body = response.body();
            Thread watchdog = new Thread(() -> {
                try {
                    while (!finished[0] && !job.cancel.get()) {
                        Thread.sleep(2000);
                        if (System.currentTimeMillis() - lastProgress[0] > 25_000) {
                            try {
                                body.close();
                            } catch (IOException ignored) {
                            }
                            return;
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            }, "cforge-download-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try (InputStream in = body;
                 var out = Files.newOutputStream(part,
                         StandardOpenOption.CREATE,
                         existing > 0 ? StandardOpenOption.APPEND
                                 : StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (job.cancel.get()) {
                        job.setState("cancelled");
                        return;
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    job.bytes += read;
                    long now = System.currentTimeMillis();
                    lastProgress[0] = now;
                    if (now - lastUpdate > 250) {
                        long deltaBytes = job.bytes - lastBytes;
                        long deltaMs = now - lastSample;
                        if (deltaMs > 0) {
                            speed = (long) (deltaBytes * 1000.0 / deltaMs);
                        }
                        job.speedBps = speed;
                        job.updatedMs = now;
                        lastUpdate = now;
                        lastBytes = job.bytes;
                        lastSample = now;
                    }
                }
            } finally {
                finished[0] = true;
                watchdog.interrupt();
            }
            if (job.cancel.get()) {
                job.setState("cancelled");
                return;
            }
            job.setState("verifying");
            job.sha256 = hex(digest.digest());
            if (!job.expectedSha256.isBlank()
                    && !job.expectedSha256.equalsIgnoreCase(job.sha256)) {
                job.fail("SHA-256 mismatch. Expected " + job.expectedSha256
                        + " but got " + job.sha256);
                return;
            }
            if (job.expectedBytes > 0 && job.bytes > 0
                    && Math.abs(job.bytes - job.expectedBytes) > Math.max(1024,
                    job.expectedBytes / 100)) {
                job.verifyNote = "下载大小 " + fmt(job.bytes) + " 与期望 "
                        + fmt(job.expectedBytes) + " 相差超过 1%，请核验来源。";
            }
            if (Files.exists(target) && !job.overwrite) {
                target = uniqueTarget(target, false);
                job.savedPath = target;
                job.filename = target.getFileName().toString();
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            part = null;
            job.savedPath = target;
            job.setState("done");
            job.message = "Downloaded " + fmt(job.bytes) + " to " + target;
        } catch (Exception ex) {
            retryOrFail(job, message(ex));
        } finally {
            if (part != null && job.cancel.get()) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                }
            }
            job.updatedMs = System.currentTimeMillis();
        }
    }

    private void retryOrFail(Job job, String reason) {
        if (job.cancel.get()) {
            job.setState("cancelled");
            return;
        }
        if ((job.candidates == null || job.candidates.isEmpty())
                && job.sameUrlRetries < 2 && job.bytes > 0
                && job.totalBytes > job.bytes) {
            ++job.sameUrlRetries;
            job.error = "";
            job.setState("connecting");
            download(job);
            return;
        }
        if (job.auto && job.candidates != null && !job.candidates.isEmpty()) {
            int attempts = 0;
            while (++job.candidateIndex < job.candidates.size() && attempts < 4) {
                Map<String, Object> next = job.candidates.get(job.candidateIndex);
                String nextUrl = Json.str(next, "url", "");
                if (nextUrl.isBlank() || job.attemptedUrls.contains(nextUrl)) {
                    continue;
                }
                ++attempts;
                job.attemptedUrls.add(nextUrl);
                job.url = nextUrl;
                job.chosen = next;
                job.bytes = 0;
                job.totalBytes = 0;
                job.speedBps = 0;
                job.sha256 = "";
                job.verifyNote = "";
                job.error = "";
                if (job.lastPartPath != null) {
                    try {
                        Files.deleteIfExists(job.lastPartPath);
                    } catch (IOException ignored) {
                    }
                }
                job.setState("connecting");
                download(job);
                return;
            }
        }
        job.fail(reason + " · 已尝试 " + Math.max(1, job.attemptedUrls.size()) + " 个候选");
    }

    private Map<String, Object> probe(URI uri, Job job, boolean wantHtml) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", userAgent())
                    .header("Accept", "*/*")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = http.send(request,
                    HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 400 || status == 405 || status == 403) {
                return probeGet(uri, wantHtml);
            }
            fillMeta(out, response.statusCode(), response.headers(), uri);
            return out;
        } catch (Exception ex) {
            return probeGet(uri, wantHtml);
        }
    }

    private Map<String, Object> probeGet(URI uri, boolean wantHtml) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", userAgent())
                    .header("Accept", "*/*")
                    .header("Range", "bytes=0-0")
                    .build();
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                in.read();
            }
            fillMeta(out, response.statusCode(), response.headers(), uri);
            long length = response.headers().firstValueAsLong("content-length").orElse(0);
            if (response.statusCode() == 206) {
                String range = response.headers().firstValue("content-range").orElse("");
                int slash = range.lastIndexOf('/');
                if (slash >= 0) {
                    try {
                        length = Long.parseLong(range.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            out.put("bytes", length);
            if (wantHtml) {
                // We do not read the page body here; callers fetch it lazily.
            }
            return out;
        } catch (Exception ex) {
            out.put("error", message(ex));
            return out;
        }
    }

    private void fillMeta(Map<String, Object> out, int status,
                          java.net.http.HttpHeaders headers, URI uri) {
        String type = headers.firstValue("content-type").orElse("");
        out.put("status", status);
        out.put("content_type", type);
        out.put("bytes", headers.firstValueAsLong("content-length").orElse(0));
        String disposition = headers.firstValue("content-disposition").orElse("");
        String name = filenameFromDisposition(disposition);
        if (name.isBlank()) {
            name = filenameFromUrl(uri);
        }
        out.put("filename", name);
        out.put("is_html", type.toLowerCase(Locale.ROOT).contains("text/html"));
        out.put("final_url", uri.toString());
    }

    /* ---------------------------------------------------------------- */
    /* candidate discovery                                               */
    /* ---------------------------------------------------------------- */

    private List<Map<String, Object>> searchCandidates(String query,
                                                       Map<String, Object> request,
                                                       String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        String product = productKey(query);
        List<Map<String, Object>> results = webResults(query, kind);
        Set<String> officialHosts = new LinkedHashSet<>();
        int checked = 0;
        for (Map<String, Object> result : results) {
            String url = Json.str(result, "url", "");
            String title = Json.str(result, "title", "");
            String snippet = Json.str(result, "snippet", "");
            if (url.isBlank() || !url.startsWith("http")) {
                continue;
            }
            if (isDirectFile(url)) {
                Map<String, Object> item = candidate(url, title, "search",
                        Json.map("snippet", snippet), kind, query);
                item.put("official", looksOfficial(result.get("host") instanceof String
                        ? (String) result.get("host") : "", title + " " + snippet, product));
                out.add(item);
            }
            if (checked < 6 && likelyOfficialPage(url, title + " " + snippet, product)) {
                String host = host(url);
                officialHosts.add(host);
                ++checked;
                for (Map<String, Object> link : linksFromPage(url, query, kind, 18)) {
                    link.put("official", true);
                    link.put("source", "official");
                    out.add(link);
                }
            }
        }
        out.addAll(githubCandidates(query, request, kind));
        // Web research is a last-resort fallback when both search engines and
        // GitHub fail; the built-in research provider is often region-limited.
        if (out.isEmpty()) {
            Map<String, Object> research;
            try {
                research = online.research(query + " official download");
            } catch (Exception ex) {
                research = Json.map("ok", false, "error", message(ex));
            }
            for (Object raw : Json.asList(research.get("results"))) {
                Map<String, Object> result = Json.asObject(raw);
                String url = Json.str(result, "url", "");
                if (url.isBlank() || !url.startsWith("http")) {
                    continue;
                }
                if (isDirectFile(url)) {
                    out.add(candidate(url, Json.str(result, "title", ""), "search",
                            result, kind, query));
                } else {
                    out.addAll(linksFromPage(url, query, kind, 10));
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> webResults(String query, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        String suffix = "official download";
        if ("game".equals(kind)) {
            suffix = "official free download game";
        } else if (List.of("file", "document", "webpage").contains(kind)) {
            suffix = "file download";
        }
        String full = query + " " + suffix;
        out.addAll(duckResults(full));
        if (out.size() < 6) {
            out.addAll(bingResults(full));
        }
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> item : out) {
            String url = Json.str(item, "url", "");
            if (url.isBlank()) {
                continue;
            }
            String host = host(url);
            if (BAD_HOSTS.contains(host) || JUNK_HOSTS.contains(host)
                    || host.endsWith(".baidu.com") || host.contains("zhidao.")) {
                continue;
            }
            String lower = (Json.str(item, "title", "") + " " + url).toLowerCase(Locale.ROOT);
            if (BAD_WORDS.stream().anyMatch(lower::contains)) {
                continue;
            }
            unique.putIfAbsent(url, item);
        }
        return new ArrayList<>(unique.values());
    }

    private List<Map<String, Object>> duckResults(String query) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            URI uri = URI.create("https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(18))
                    .header("User-Agent", userAgent())
                    .header("Accept", "text/html")
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return out;
            }
            Matcher matcher = Pattern.compile(
                    "(?is)class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)(?=class=\"result__a\"|$)")
                    .matcher(response.body());
            while (matcher.find() && out.size() < 12) {
                String href = matcher.group(1).replace("&amp;", "&");
                String title = stripTags(matcher.group(2));
                String snippet = stripTags(matcher.group(3));
                if (href.startsWith("//")) {
                    href = "https:" + href;
                }
                if (href.contains("uddg=")) {
                    try {
                        String encoded = href.substring(href.indexOf("uddg=") + 5);
                        int amp = encoded.indexOf('&');
                        if (amp >= 0) {
                            encoded = encoded.substring(0, amp);
                        }
                        href = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        continue;
                    }
                }
                if (!href.startsWith("http") || href.contains("duckduckgo.com/l/")) {
                    continue;
                }
                out.add(Json.map("title", title, "url", href,
                        "snippet", snippet, "host", host(href), "engine", "duckduckgo"));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<Map<String, Object>> bingResults(String query) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            URI uri = URI.create("https://www.bing.com/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&format=rss&count=20");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(18))
                    .header("User-Agent", userAgent())
                    .header("Accept", "application/rss+xml,text/xml")
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return out;
            }
            Matcher matcher = Pattern.compile("(?is)<item>(.*?)</item>")
                    .matcher(response.body());
            while (matcher.find() && out.size() < 12) {
                String block = matcher.group(1);
                String title = xmlText(block, "title");
                String link = xmlText(block, "link");
                String description = xmlText(block, "description");
                if (!link.startsWith("http")) {
                    continue;
                }
                out.add(Json.map("title", stripTags(title), "url", link,
                        "snippet", stripTags(description), "host", host(link),
                        "engine", "bing"));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String xmlText(String block, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag
                + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + tag + ">")
                .matcher(block);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static boolean likelyOfficialPage(String url, String text, String product) {
        String host = host(url);
        if (host.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String compactHost = host.replace("-", "").replace(".", "");
        if (!product.isEmpty() && compactHost.contains(product)) {
            return true;
        }
        if (lower.contains("official") || lower.contains("官方")) {
            return true;
        }
        String compact = productKey(text);
        return !product.isEmpty() && compact.contains(product)
                && !JUNK_HOSTS.contains(host);
    }

    private static boolean looksOfficial(String host, String text, String product) {
        return likelyOfficialPage("https://" + host + "/", text, product);
    }

    private List<Map<String, Object>> githubCandidates(String query,
                                                       Map<String, Object> request,
                                                       String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String token = keys.secretValue("github_token");
            String search = URLEncoder.encode(query + " in:name,description", StandardCharsets.UTF_8);
            HttpRequest repos = HttpRequest.newBuilder(URI.create(
                            "https://api.github.com/search/repositories?q=" + search
                                    + "&per_page=8"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", userAgent())
                    .header("Authorization", token.isBlank() ? "" : "Bearer " + token)
                    .build();
            HttpResponse<String> response = http.send(repos,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return out;
            }
            Map<String, Object> doc = Json.asObject(Json.parse(response.body()));
            for (Object raw : Json.asList(doc.get("items"))) {
                Map<String, Object> repo = Json.asObject(raw);
                String full = Json.str(repo, "full_name", "");
                String desc = Json.str(repo, "description", "");
                if (full.isBlank()) {
                    continue;
                }
                HttpRequest release = HttpRequest.newBuilder(URI.create(
                                "https://api.github.com/repos/" + full + "/releases/latest"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", userAgent())
                        .header("Authorization", token.isBlank() ? "" : "Bearer " + token)
                        .build();
                HttpResponse<String> releaseResponse = http.send(release,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (releaseResponse.statusCode() != 200) {
                    continue;
                }
                Map<String, Object> releaseDoc = Json.asObject(
                        Json.parse(releaseResponse.body()));
                String tag = Json.str(releaseDoc, "tag_name", "");
                for (Object assetRaw : Json.asList(releaseDoc.get("assets"))) {
                    Map<String, Object> asset = Json.asObject(assetRaw);
                    String url = Json.str(asset, "browser_download_url", "");
                    String name = Json.str(asset, "name", "");
                    if (url.isBlank() || name.isBlank()) {
                        continue;
                    }
                    long size = Json.lon(asset, "size", 0);
                    Map<String, Object> item = candidate(url, name,
                            "github", Json.map("title", full + " " + tag,
                                    "snippet", desc), kind, query);
                    item.put("bytes", size);
                    item.put("official", false);
                    item.put("repo", full);
                    item.put("source_host", "github.com");
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<Map<String, Object>> candidatesFromUrl(String url, String query,
                                                        String kind,
                                                        Map<String, Object> request) {
        List<Map<String, Object>> out = new ArrayList<>();
        String ext = extension(filenameFromUrl(URI.create(url)));
        boolean htmlLink = "html".equals(ext) || "htm".equals(ext);
        if (isDirectFile(url) && (!htmlLink || "webpage".equals(kind))) {
            out.add(candidate(url, "", "link", Json.map(), kind, query));
            return out;
        }
        out.addAll(linksFromPage(url, query, kind, 30));
        return out;
    }

    private List<Map<String, Object>> linksFromPage(String pageUrl, String query,
                                                    String kind, int maxLinks) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectPageLinks(pageUrl, query, kind, maxLinks, 0, out);
        return out;
    }

    private void collectPageLinks(String pageUrl, String query, String kind,
                                  int maxLinks, int depth,
                                  List<Map<String, Object>> out) {
        if (depth > 1 || out.size() >= maxLinks) {
            return;
        }
        List<String> nested = new ArrayList<>();
        try {
            URI base = new URI(pageUrl);
            HttpRequest request = HttpRequest.newBuilder(base)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", userAgent())
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return;
            }
            String type = response.headers().firstValue("content-type").orElse("")
                    .toLowerCase(Locale.ROOT);
            String html = response.body();
            String title = pageTitle(html);
            if (!type.contains("html")) {
                if (isDirectFile(pageUrl)) {
                    out.add(candidate(pageUrl, title, "link", Json.map(), kind, query));
                }
                return;
            }
            if (BAD_WORDS.stream().anyMatch(word -> (title + " " + pageUrl)
                    .toLowerCase(Locale.ROOT).contains(word))) {
                return;
            }
            Matcher matcher = HREF.matcher(html);
            Set<String> seen = new LinkedHashSet<>();
            while (matcher.find() && out.size() < maxLinks) {
                String href = matcher.group(1).trim();
                String anchor = stripTags(matcher.group(2));
                if (href.startsWith("#") || href.startsWith("javascript:")
                        || href.startsWith("mailto:") || href.startsWith("magnet:")
                        || href.endsWith(".torrent")) {
                    continue;
                }
                String absolute;
                try {
                    absolute = base.resolve(href.replace(" ", "%20")).toString();
                } catch (Exception ex) {
                    continue;
                }
                if (!absolute.startsWith("http") || !seen.add(absolute)) {
                    continue;
                }
                String ext = extension(filenameFromUrl(URI.create(absolute)));
                boolean htmlLink = "html".equals(ext) || "htm".equals(ext);
                boolean deliverable = FILE_EXT.contains(ext)
                        && (!htmlLink || "webpage".equals(kind));
                boolean looksFile = deliverable || containsDownloadWord(anchor)
                        || containsDownloadWord(href);
                if (deliverable) {
                    Map<String, Object> item = candidate(absolute, anchor,
                            "page", Json.map("page", pageUrl, "title", title), kind, query);
                    item.put("page", pageUrl);
                    item.put("page_title", title);
                    long labelSize = sizeFromLabel(anchor + " " + href);
                    if (labelSize > 0) {
                        item.put("bytes", labelSize);
                    }
                    out.add(item);
                    continue;
                }
                if (htmlLink && looksFile && depth == 0
                        && host(absolute).equals(host(pageUrl))
                        && nested.size() < 3) {
                    nested.add(absolute);
                } else if (looksFile && !FILE_EXT.contains(ext)
                        && containsDownloadWord(anchor) && nested.size() < 1) {
                    nested.add(absolute);
                }
            }
        } catch (Exception ignored) {
        }
        for (String url : nested) {
            if (out.size() >= maxLinks) {
                break;
            }
            collectPageLinks(url, query, kind, maxLinks, depth + 1, out);
        }
    }

    private Map<String, Object> candidate(String url, String title, String source,
                                          Map<String, Object> extra,
                                          String kind, String query) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("title", title == null ? "" : title);
        out.put("source", source);
        out.put("source_host", host(url));
        out.put("filename", filenameFromUrl(URI.create(url)));
        out.put("kind", kind);
        out.put("score", 0.0);
        out.put("reasons", new ArrayList<String>());
        if (!extra.isEmpty()) {
            out.put("page", Json.str(extra, "page", ""));
            out.put("snippet", cap(Json.str(extra, "snippet", ""), 240));
        }
        return out;
    }

    private List<Map<String, Object>> dedupe(List<Map<String, Object>> candidates) {
        Map<String, Map<String, Object>> byUrl = new LinkedHashMap<>();
        for (Map<String, Object> item : candidates) {
            String url = Json.str(item, "url", "");
            if (url.isBlank()) {
                continue;
            }
            if (BAD_HOSTS.contains(host(url))) {
                continue;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains("torrent") || lower.contains("magnet:")) {
                continue;
            }
            if (BAD_WORDS.stream().anyMatch(lower::contains)) {
                continue;
            }
            byUrl.putIfAbsent(url, item);
        }
        return new ArrayList<>(byUrl.values());
    }

    private List<Map<String, Object>> rank(List<Map<String, Object>> raw,
                                           String query,
                                           Map<String, Object> request) {
        List<Map<String, Object>> candidates = dedupe(raw);
        scoreAll(candidates, query, request);
        candidates.sort(Comparator.comparingDouble(
                (Map<String, Object> item) -> Json.decimal(item, "score", 0)).reversed());
        enrichSizes(candidates, 14);
        scoreAll(candidates, query, request);
        candidates.sort(Comparator.comparingDouble(
                (Map<String, Object> item) -> Json.decimal(item, "score", 0)).reversed());
        if (candidates.size() > MAX_CANDIDATES) {
            return new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }
        return candidates;
    }

    private void enrichSizes(List<Map<String, Object>> candidates, int max) {
        List<java.util.concurrent.CompletableFuture<?>> futures = new ArrayList<>();
        int checked = 0;
        for (Map<String, Object> item : candidates) {
            if (checked >= max) {
                break;
            }
            if (Json.lon(item, "bytes", 0) > 0) {
                continue;
            }
            String url = Json.str(item, "url", "");
            if (!url.startsWith("http") || !isDirectFile(url)
                    || List.of("html", "htm").contains(
                    extension(filenameFromUrl(URI.create(url))))) {
                continue;
            }
            ++checked;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .version(HttpClient.Version.HTTP_1_1)
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", userAgent())
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();
                futures.add(http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .thenAccept(response -> {
                            long size = response.headers()
                                    .firstValueAsLong("content-length").orElse(0);
                            if (size > 0) {
                                item.put("bytes", size);
                            }
                        })
                        .exceptionally(error -> null));
            } catch (Exception ignored) {
            }
        }
        if (futures.isEmpty()) {
            return;
        }
        try {
            java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(9, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            for (java.util.concurrent.CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    private void scoreAll(List<Map<String, Object>> candidates, String query,
                          Map<String, Object> request) {
        double expectedMb = Json.decimal(request, "expected_size_mb", 0);
        long expected = Math.round(expectedMb * 1024 * 1024);
        String kind = Json.str(request, "kind", "software").toLowerCase(Locale.ROOT);
        if (kind.isBlank()) {
            kind = "software";
        }
        String platform = Json.str(request, "platform", currentPlatform())
                .toLowerCase(Locale.ROOT);
        List<String> tokens = tokens(query);
        String product = productKey(query);
        String compound = compact(query);
        Map<String, List<Long>> sizeGroups = new LinkedHashMap<>();
        for (Map<String, Object> item : candidates) {
            String name = Json.str(item, "filename", "").toLowerCase(Locale.ROOT);
            long size = Json.lon(item, "bytes", 0);
            if (size > 0) {
                sizeGroups.computeIfAbsent(name, k -> new ArrayList<>()).add(size);
            }
        }
        for (Map<String, Object> item : candidates) {
            double score = 0;
            List<String> reasons = new ArrayList<>();
            String url = Json.str(item, "url", "").toLowerCase(Locale.ROOT);
            String name = Json.str(item, "filename", "").toLowerCase(Locale.ROOT);
            String ext = extension(name);
            long size = Json.lon(item, "bytes", 0);
            String page = Json.str(item, "page", "").toLowerCase(Locale.ROOT);
            if (!url.startsWith("https://")) {
                score -= 8;
                reasons.add("non-HTTPS");
            }
            if (!FILE_EXT.contains(ext)) {
                score -= 15;
                reasons.add("unknown file type");
            }
            if (("html".equals(ext) || "htm".equals(ext)) && !"webpage".equals(kind)) {
                score -= 120;
                reasons.add("web page, not a file");
            }
            if ("software".equals(kind)) {
                if (SOFTWARE_EXT.contains(ext)) {
                    score += 20;
                    reasons.add("installable/archive");
                }
                if (platform.contains("win") && List.of("exe", "msi", "msix", "zip", "7z")
                        .contains(ext)) {
                    score += 15;
                    reasons.add("Windows package");
                }
                if (platform.contains("mac") && List.of("dmg", "pkg", "zip").contains(ext)) {
                    score += 15;
                    reasons.add("macOS package");
                }
                if (platform.contains("linux") && List.of("deb", "rpm", "appimage", "tar",
                        "gz", "zip").contains(ext)) {
                    score += 15;
                    reasons.add("Linux package");
                }
            } else if ("game".equals(kind)) {
                if (List.of("zip", "7z", "exe", "msi", "dmg", "pkg", "iso", "img")
                        .contains(ext)) {
                    score += 14;
                    reasons.add("game package");
                }
            } else if (DOC_EXT.contains(ext)) {
                score += 20;
                reasons.add("document file");
            } else if (List.of("webpage", "file").contains(kind) && FILE_EXT.contains(ext)) {
                score += 12;
                reasons.add("file type");
            }
            String compactName = compact(name);
            String repo = compact(Json.str(item, "repo", ""));
            String context = compact(name + " " + Json.str(item, "title", "") + " "
                    + Json.str(item, "page_title", "") + " "
                    + Json.str(item, "snippet", "") + " " + page);
            boolean nameHasProduct = !product.isEmpty() && context.contains(product);
            boolean allTokens = !tokens.isEmpty()
                    && tokens.stream().allMatch(context::contains);
            boolean firstToken = !tokens.isEmpty() && context.contains(tokens.get(0));
            if (nameHasProduct) {
                score += 45;
                reasons.add("name matches product");
            } else if (allTokens) {
                score += 24;
                reasons.add("all name terms present");
            } else if (firstToken) {
                score += 10;
            } else if (!tokens.isEmpty()) {
                score -= 35;
                reasons.add("different product name");
            }
            if (Json.bool(item, "official", false)) {
                score += 55;
                reasons.add("official source");
                if (!host(url).contains("github") && !host(url).contains("gitcode")) {
                    score += 6;
                    reasons.add("official domain");
                }
            } else if (url.contains("github.com") || url.contains("githubusercontent.com")
                    || url.contains("sourceforge.net") || url.contains("itch.io")) {
                score += 5;
            } else if (page.contains(host(url).replace("www.", ""))) {
                score += 7;
            }
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (List.of("debug", "symbols", "symbol", "source", "src", "checksum",
                    "sha256", "translations", "docs", "sdk", "headers", "lib")
                    .stream().anyMatch(lowerName::contains)) {
                score -= 22;
                reasons.add("not the main program");
            }
            if (List.of("discord", "rpc", "plugin", "addon", "add-on", "extension",
                    "theme", "skin", "wallpaper", "language-pack", "translations")
                    .stream().anyMatch(lowerName::contains)) {
                score -= 35;
                reasons.add("secondary component");
            }
            if (platform.contains("win")) {
                if (lowerName.contains("arm64") || lowerName.contains("aarch64")) {
                    score -= 12;
                }
                if (lowerName.contains("linux") || lowerName.contains("macos")
                        || lowerName.contains("darwin") || lowerName.contains("android")) {
                    score -= 30;
                    reasons.add("other platform");
                }
            }
            if (lowerName.contains("x64") || lowerName.contains("win64")
                    || lowerName.contains("64-bit") || lowerName.contains("amd64")) {
                score += 8;
                reasons.add("64-bit build");
            }
            if (containsDownloadWord(name)) {
                score += 5;
                reasons.add("download link");
            }
            if (size > 0) {
                score += 8;
                reasons.add("size reported");
                List<Long> group = sizeGroups.get(name);
                if (group != null && group.size() > 1) {
                    long modal = group.stream()
                            .collect(java.util.stream.Collectors.groupingBy(x -> x,
                                    java.util.stream.Collectors.counting()))
                            .entrySet().stream()
                            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0L);
                    if (Math.abs(size - modal) <= Math.max(4096, modal / 200)) {
                        score += 10;
                        reasons.add("size corroborated");
                    } else {
                        score -= 6;
                        reasons.add("conflicting size");
                    }
                }
            }
            if (expected > 0 && size > 0) {
                double diff = Math.abs(size - (double) expected) / expected;
                if (diff <= 0.02) {
                    score += 45;
                    reasons.add("matches expected size");
                } else if (diff <= 0.10) {
                    score += 25;
                    reasons.add("near expected size");
                } else if (diff > 0.5) {
                    score -= 15;
                    reasons.add("far from expected size");
                }
            } else if (expected > 0 && size <= 0) {
                score -= 5;
                reasons.add("size unknown");
            }
            String lowerPage = (Json.str(item, "title", "") + " " + page)
                    .toLowerCase(Locale.ROOT);
            if (BAD_WORDS.stream().anyMatch(lowerPage::contains)) {
                score -= 80;
                reasons.add("crack/warez wording");
            }
            item.put("score", Math.round(score * 10.0) / 10.0);
            item.put("reasons", reasons);
        }
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                           */
    /* ---------------------------------------------------------------- */

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\u4e00-\u9fff]", "");
    }

    private static String productKey(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (String token : lower.split("[\\s\\p{Punct}]+")) {
            if (token.isBlank() || STOP_WORDS.contains(token)
                    || token.matches("[0-9.]+") && token.length() <= 2) {
                continue;
            }
            out.append(token);
        }
        if (out.length() == 0) {
            out.append(compact(query));
        }
        return out.toString();
    }

    private static long sizeFromLabel(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile(
                "(?i)(\\d+(?:[.,]\\d+)?)\\s*(kb|mb|gb|kib|mib|gib|b)\\b")
                .matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        try {
            double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            long factor = switch (unit) {
                case "tb" -> 1L << 40;
                case "gb", "gib" -> 1L << 30;
                case "mb", "mib" -> 1L << 20;
                case "kb", "kib" -> 1L << 10;
                default -> 1;
            };
            return Math.round(value * factor);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static List<String> tokens(String query) {
        List<String> out = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s\\p{Punct}]+")) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    private static boolean containsDownloadWord(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("download") || lower.contains("下载")
                || lower.contains("release") || lower.contains("setup")
                || lower.contains("installer") || lower.contains("mirror");
    }

    private static boolean isDirectFile(String url) {
        String name = filenameFromUrl(URI.create(url)).toLowerCase(Locale.ROOT);
        return FILE_EXT.contains(extension(name));
    }

    private static String filenameFromUrl(URI uri) {
        try {
            String path = uri.getPath();
            if (path == null) {
                return "";
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
            return sanitizeFilename(name);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String filenameFromDisposition(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher star = Pattern.compile("(?i)filename\\*=UTF-8''([^;]+)").matcher(value);
        if (star.find()) {
            try {
                return sanitizeFilename(URLDecoder.decode(star.group(1).trim(),
                        StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        Matcher plain = Pattern.compile("(?i)filename=\"?([^\";]+)\"?").matcher(value);
        if (plain.find()) {
            return sanitizeFilename(plain.group(1).trim());
        }
        return "";
    }

    private static String sanitizeFilename(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.length() > 180) {
            String ext = extension(name);
            name = name.substring(0, 170) + (ext.isEmpty() ? "" : "." + ext);
        }
        return name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extensionForType(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.contains("pdf")) return "pdf";
        if (lower.contains("zip")) return "zip";
        if (lower.contains("msword")) return "doc";
        if (lower.contains("officedocument.wordprocessingml")) return "docx";
        if (lower.contains("officedocument.spreadsheetml")) return "xlsx";
        if (lower.contains("officedocument.presentationml")) return "pptx";
        if (lower.contains("html")) return "html";
        if (lower.contains("json")) return "json";
        if (lower.contains("text/plain")) return "txt";
        if (lower.contains("octet-stream")) return "bin";
        return "";
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        return "linux";
    }

    private static String stripTags(String html) {
        return html == null ? "" : html.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private static String pageTitle(String html) {
        Matcher matcher = TITLE.matcher(html == null ? "" : html);
        return matcher.find() ? stripTags(matcher.group(1)) : "";
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private Path resolveTarget(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return downloads.toAbsolutePath().normalize();
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

    private Path resolve(String raw) {
        return resolveTarget(raw);
    }

    private static Path uniqueTarget(Path path, boolean overwrite) {
        if (overwrite || !Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        String base = name;
        String ext = extension(name);
        if (!ext.isEmpty()) {
            base = name.substring(0, name.length() - ext.length() - 1);
        }
        for (int i = 1; i < 10_000; ++i) {
            Path candidate = path.resolveSibling(base + " (" + i + ")"
                    + (ext.isEmpty() ? "" : "." + ext));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return path.resolveSibling(System.nanoTime() + "-" + name);
    }

    private static String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36 CodeForge-AI/1.2.0";
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) {
            out.append(String.format("%02x", value));
        }
        return out.toString();
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024;
            ++unit;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private Map<String, Object> view(Job job) {
        Map<String, Object> out = job.toMap();
        out.put("ok", true);
        out.put("progress", job.totalBytes > 0
                ? Math.min(100, Math.round(job.bytes * 1000.0 / job.totalBytes) / 10.0)
                : (job.state.equals("done") ? 100 : 0));
        out.put("bytes_human", fmt(job.bytes));
        out.put("total_human", job.totalBytes > 0 ? fmt(job.totalBytes) : "unknown");
        out.put("speed_human", job.speedBps > 0 ? fmt(job.speedBps) + "/s" : "");
        out.put("eta_sec", job.speedBps > 0 && job.totalBytes > job.bytes
                ? (job.totalBytes - job.bytes) / job.speedBps : -1);
        return out;
    }

    private static final class Job {
        final String id;
        final String createdAt = Instant.now().toString();
        final long createdMs = System.currentTimeMillis();
        volatile String state = "queued";
        String url;
        final String query;
        Path targetDir;
        String filename = "";
        String kind = "software";
        String platform = "";
        long bytes;
        long totalBytes;
        long speedBps;
        long expectedBytes;
        long maxBytes;
        String expectedSha256 = "";
        String sha256 = "";
        String contentType = "";
        String error = "";
        String message = "";
        String verifyNote = "";
        boolean auto = true;
        boolean savePage;
        boolean overwrite;
        Path savedPath;
        Path lastPartPath;
        Map<String, Object> chosen;
        int candidateIndex = -1;
        int sameUrlRetries;
        final Set<String> attemptedUrls = new LinkedHashSet<>();
        List<Map<String, Object>> candidates = List.of();
        final AtomicBoolean cancel = new AtomicBoolean(false);
        volatile long updatedMs = System.currentTimeMillis();
        volatile long durationMs;

        Job(String id, String url, String query) {
            this.id = id;
            this.url = url == null ? "" : url;
            this.query = query == null ? "" : query;
        }

        void setState(String value) {
            this.state = value;
            this.updatedMs = System.currentTimeMillis();
        }

        void fail(String message) {
            this.error = message == null ? "download failed" : message;
            this.state = "failed";
            this.updatedMs = System.currentTimeMillis();
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("state", state);
            out.put("url", url);
            out.put("query", query);
            out.put("target_dir", targetDir == null ? "" : targetDir.toString());
            out.put("filename", filename);
            out.put("kind", kind);
            out.put("platform", platform);
            out.put("bytes", bytes);
            out.put("total_bytes", totalBytes);
            out.put("speed_bps", speedBps);
            out.put("expected_bytes", expectedBytes);
            out.put("sha256", sha256);
            out.put("content_type", contentType);
            out.put("error", error);
            out.put("message", message);
            out.put("verify_note", verifyNote);
            out.put("saved_path", savedPath == null ? "" : savedPath.toString());
            out.put("chosen", chosen);
            out.put("candidates", candidates);
            out.put("created_at", createdAt);
            out.put("created_ms", createdMs);
            out.put("updated_ms", updatedMs);
            out.put("duration_ms", durationMs);
            return out;
        }
    }
}