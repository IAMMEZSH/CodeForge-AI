package codeforge.server;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network awareness, geolocation, web research and law-source refresh.
 * Everything fetched online is also written into the local memory store so
 * the assistant keeps the knowledge after the connection drops.
 */
public final class OnlineService {
    private static final Pattern DDG_ANCHOR = Pattern.compile(
            "<a\\b[^>]*\\bclass=\"[^\"]*result__a[^\"]*\"[^>]*>(.*?)</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "<([a-z]+)\\b[^>]*\\bclass=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</\\1>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile(
            "href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SO_ITEM = Pattern.compile(
            "<li[^>]+class=\"[^\"]*res-list[^\"]*\"[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SO_LINK = Pattern.compile(
            "<h3[^>]*class=\"[^\"]*res-title[^\"]*\"[^>]*>.*?<a[^>]*>(.*?)</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_URL = Pattern.compile(
            "data-mdurl=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SO_SNIPPET = Pattern.compile(
            "<p[^>]*class=\"[^\"]*res-desc[^\"]*\"[^>]*>(.*?)</p>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern SCRIPT = Pattern.compile(
            "<(script|style)\\b[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final long NETWORK_TTL_MS = 60_000L;
    private static final long GEO_TTL_MS = 24L * 3600L * 1000L;
    private static final long FAILURE_COOLDOWN_MS = 5L * 60L * 1000L;

    public static final class Brief {
        public String jurisdiction = "";
        public String country = "";
        public String context = "";
        public String source = "";
        public String fetchedAt = "";
        public String error = "";
        public long at;
    }

    private final AppSettings settings;
    private final MemoryStore memory;
    private final HttpClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "codeforge-online");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Brief> briefs = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile boolean networkOk;
    private volatile long networkAt;
    private volatile Map<String, Object> geo;
    private volatile long geoAt;
    private volatile long researchFailedAt;
    private volatile String lastResearchAt = "";
    private volatile String lastResearchSource = "";
    private volatile String lastResearchQuery = "";

    public OnlineService(AppSettings settings, MemoryStore memory) {
        this.settings = settings;
        this.memory = memory;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean online() {
        if (!settings.onlineEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (networkAt != 0 && now - networkAt < NETWORK_TTL_MS) {
            return networkOk;
        }
        synchronized (this) {
            if (networkAt != 0 && System.currentTimeMillis() - networkAt < NETWORK_TTL_MS) {
                return networkOk;
            }
            boolean ok = false;
            Map<String, Object> detected = tryGeoIpWho();
            if (detected != null) {
                geo = detected;
                geoAt = System.currentTimeMillis();
                ok = true;
            } else {
                detected = tryGeoIpApiCo();
                if (detected != null) {
                    geo = detected;
                    geoAt = System.currentTimeMillis();
                    ok = true;
                }
            }
            if (!ok) {
                ok = probe("https://api.github.com/zen", 4000) != null;
            }
            if (!ok) {
                ok = probe("https://www.baidu.com/", 4000) != null;
            }
            networkOk = ok;
            networkAt = System.currentTimeMillis();
            return ok;
        }
    }

    public Map<String, Object> geo() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = geo;
        if (cached != null && now - geoAt < GEO_TTL_MS) {
            return cached;
        }
        if (!online()) {
            return fallbackGeo("offline");
        }
        cached = geo;
        if (cached != null) {
            return cached;
        }
        return fallbackGeo("locale");
    }

    private Map<String, Object> tryGeoIpWho() {
        String body = fetch("https://ipwho.is/", 5000, null);
        if (body == null) {
            return null;
        }
        try {
            Map<String, Object> doc = Json.asObject(Json.parse(body));
            if (!Json.bool(doc, "success", false)) {
                return null;
            }
            String code = Json.str(doc, "country_code", "");
            if (code.isBlank()) {
                return null;
            }
            Map<String, Object> zone = Json.asObject(doc.get("timezone"));
            Map<String, Object> connection = Json.asObject(doc.get("connection"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("source", "ipwho.is");
            out.put("confidence", "high");
            out.put("country_code", code.toUpperCase(Locale.ROOT));
            out.put("country", Json.str(doc, "country", ""));
            out.put("region", Json.str(doc, "region", ""));
            out.put("city", Json.str(doc, "city", ""));
            out.put("timezone", Json.str(zone, "id", ""));
            out.put("isp", Json.str(connection, "isp", ""));
            out.put("ip", Json.str(doc, "ip", ""));
            out.put("checked_at", Instant.now().toString());
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> tryGeoIpApiCo() {
        String body = fetch("https://ipapi.co/json/", 5000, null);
        if (body == null) {
            return null;
        }
        try {
            Map<String, Object> doc = Json.asObject(Json.parse(body));
            String code = Json.str(doc, "country_code", "");
            if (code.isBlank() || "undefined".equalsIgnoreCase(code)) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("source", "ipapi.co");
            out.put("confidence", "high");
            out.put("country_code", code.toUpperCase(Locale.ROOT));
            out.put("country", Json.str(doc, "country_name", ""));
            out.put("region", Json.str(doc, "region", ""));
            out.put("city", Json.str(doc, "city", ""));
            out.put("timezone", Json.str(doc, "timezone", ""));
            out.put("isp", Json.str(doc, "org", ""));
            out.put("ip", Json.str(doc, "ip", ""));
            out.put("checked_at", Instant.now().toString());
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> fallbackGeo(String reason) {
        String zone = TimeZone.getDefault().getID();
        String country = "";
        Locale locale = Locale.getDefault();
        if (!locale.getCountry().isBlank()) {
            country = new Locale("", locale.getCountry()).getDisplayCountry(Locale.ENGLISH);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("source", "locale");
        out.put("confidence", "low");
        out.put("country_code", locale.getCountry().toUpperCase(Locale.ROOT));
        out.put("country", country);
        out.put("region", "");
        out.put("city", "");
        out.put("timezone", zone);
        out.put("reason", reason);
        out.put("checked_at", Instant.now().toString());
        return out;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", settings.onlineEnabled());
        out.put("online", networkAt != 0 && networkOk);
        out.put("checked_at", networkAt == 0 ? "" :
                Instant.ofEpochMilli(networkAt).toString());
        out.put("research", settings.researchMode());
        out.put("search_endpoint", settings.searchEndpoint().isEmpty()
                ? "built-in (DuckDuckGo, 360 Search, Wikipedia)" : settings.searchEndpoint());
        out.put("geo", geo);
        out.put("last_research_at", lastResearchAt);
        out.put("last_research_source", lastResearchSource);
        out.put("last_research_query", lastResearchQuery);
        List<Object> packs = new ArrayList<>();
        for (Brief brief : briefs.values()) {
            packs.add(Json.map(
                    "jurisdiction", brief.jurisdiction,
                    "country", brief.country,
                    "source", brief.source,
                    "fetched_at", brief.fetchedAt,
                    "cached", true,
                    "error", brief.error));
        }
        out.put("legal_briefs", packs);
        return out;
    }

    public boolean shouldResearch(String query) {
        if (!settings.onlineEnabled()) {
            return false;
        }
        String mode = settings.researchMode();
        if (mode.equals("off")) {
            return false;
        }
        if (mode.equals("always")) {
            return true;
        }
        String text = query == null ? "" : query.trim();
        if (text.length() < 6) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String[] markers = {
                "?", "？", "how", "why", "what", "when", "where", "which", "who",
                "latest", "current", "today", "news", "docs", "documentation",
                "error", "exception", "version", "release", "law", "legal", "act",
                "最新", "今天", "现在", "当前", "新闻", "法律", "法规", "法条",
                "判例", "政策", "规定", "文档", "版本", "报错", "错误", "怎么",
                "如何", "为什么", "什么", "请问", "查一下", "搜索"};
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return text.length() >= 24;
    }

    public Map<String, Object> research(String query) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("query", query == null ? "" : query);
        if (!settings.onlineEnabled()) {
            out.put("error", "Online research is disabled in config.json");
            return out;
        }
        if (System.currentTimeMillis() - researchFailedAt < FAILURE_COOLDOWN_MS) {
            out.put("error", "Network unavailable; retrying later");
            out.put("online", false);
            return out;
        }
        if (!online()) {
            out.put("error", "This device appears to be offline");
            out.put("online", false);
            return out;
        }
        List<Map<String, Object>> results = fetchResults(query, settings.maxResults());
        if (results.isEmpty()) {
            researchFailedAt = System.currentTimeMillis();
            out.put("online", true);
            out.put("error", "No search results could be retrieved");
            return out;
        }
        List<Map<String, Object>> relevant = new ArrayList<>();
        for (Map<String, Object> item : results) {
            if (relevance(query, item) > 0) {
                relevant.add(item);
            }
        }
        if (relevant.isEmpty()) {
            out.put("online", true);
            out.put("error", "Search results were not relevant to this query");
            return out;
        }
        results = relevant;
        String source = (String) results.get(0).get("_source");
        String fetchedAt = Instant.now().toString();
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Map<String, Object> item : results) {
            clean.add(Json.map(
                    "title", item.get("title"),
                    "url", item.get("url"),
                    "snippet", item.get("snippet")));
            if (settings.memoryEnabled()) {
                memory.add("Web result: " + item.get("title") + " — " + item.get("snippet")
                                + " [" + item.get("url") + "]",
                        "web", String.valueOf(item.get("url")), "");
            }
        }
        String context = buildResearchContext(query, clean, fetchedAt, source);
        lastResearchAt = fetchedAt;
        lastResearchSource = source;
        lastResearchQuery = query;
        out.put("ok", true);
        out.put("online", true);
        out.put("source", source);
        out.put("fetched_at", fetchedAt);
        out.put("results", clean);
        out.put("context", context);
        return out;
    }

    private List<Map<String, Object>> fetchResults(String query, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        String endpoint = settings.searchEndpoint();
        if (!endpoint.isBlank()) {
            results = searx(endpoint, query, limit);
        }
        if (results.isEmpty()) {
            results = duckduckgo(query, limit);
        }
        if (results.isEmpty()) {
            results = so360(query, limit);
        }
        if (results.isEmpty()) {
            results = wikipedia(query, limit);
        }
        return results;
    }

    private static int relevance(String query, Map<String, Object> item) {
        List<String> tokens = MemoryStore.tokens(query);
        if (tokens.isEmpty()) {
            return 1;
        }
        String text = (String.valueOf(item.get("title")) + " "
                + String.valueOf(item.get("snippet"))).toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (text.contains(token)) {
                ++hits;
            }
        }
        return hits;
    }

    private List<Map<String, Object>> duckduckgo(String query, int limit) {
        String body = fetch("https://html.duckduckgo.com/html/?q=" + encode(query),
                settings.onlineTimeoutMs(), BROWSER_UA);
        if (body == null) {
            return List.of();
        }
        List<String> titles = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        Matcher anchor = DDG_ANCHOR.matcher(body);
        while (anchor.find() && urls.size() < limit * 2) {
            Matcher href = HREF.matcher(anchor.group());
            if (!href.find()) {
                continue;
            }
            String url = unwrapDuckDuckGo(href.group(1));
            if (url.isBlank()) {
                continue;
            }
            urls.add(url);
            titles.add(clean(anchor.group(1)));
        }
        List<String> snippets = new ArrayList<>();
        Matcher snippet = DDG_SNIPPET.matcher(body);
        while (snippet.find() && snippets.size() < limit * 2) {
            snippets.add(clean(snippet.group(2)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < urls.size() && out.size() < limit; ++i) {
            String title = i < titles.size() ? titles.get(i) : urls.get(i);
            String text = i < snippets.size() ? snippets.get(i) : "";
            if (title.isBlank() && text.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", title);
            item.put("url", urls.get(i));
            item.put("snippet", trim(text, 480));
            item.put("_source", "duckduckgo");
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> so360(String query, int limit) {
        String body = fetch("https://www.so.com/s?q=" + encode(query),
                settings.onlineTimeoutMs(), BROWSER_UA);
        if (body == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Matcher item = SO_ITEM.matcher(body);
        int start = -1;
        while (item.find() && out.size() < limit) {
            if (start >= 0) {
                addSoBlock(body.substring(start, item.start()), out);
            }
            start = item.start();
        }
        if (start >= 0 && out.size() < limit) {
            addSoBlock(body.substring(start, Math.min(body.length(), start + 20000)), out);
        }
        return out;
    }

    private void addSoBlock(String block, List<Map<String, Object>> out) {
        Matcher link = SO_LINK.matcher(block);
        if (!link.find()) {
            return;
        }
        Matcher md = MD_URL.matcher(link.group());
        if (!md.find()) {
            return;
        }
        String url = decodeEntities(md.group(1));
        if (!url.startsWith("http")) {
            return;
        }
        String title = clean(link.group(1));
        if (title.isBlank()) {
            return;
        }
        String snippet = "";
        Matcher snip = SO_SNIPPET.matcher(block);
        if (snip.find()) {
            snippet = clean(snip.group(1));
        }
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("title", title);
        hit.put("url", url);
        hit.put("snippet", trim(snippet, 480));
        hit.put("_source", "360-search");
        out.add(hit);
    }

    private List<Map<String, Object>> wikipedia(String query, int limit) {
        boolean han = containsHan(query);
        String lang = han ? "zh" : "en";
        String url = "https://" + lang + ".wikipedia.org/w/api.php?action=query&list=search"
                + "&format=json&utf8=1&srlimit=" + limit + "&srsearch=" + encode(query);
        String body = fetch(url, settings.onlineTimeoutMs(), null);
        if (body == null) {
            return List.of();
        }
        try {
            Map<String, Object> doc = Json.asObject(Json.parse(body));
            Map<String, Object> result = Json.asObject(doc.get("query"));
            List<Object> hits = Json.asList(result.get("search"));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object raw : hits) {
                Map<String, Object> hit = Json.asObject(raw);
                String title = Json.str(hit, "title", "");
                if (title.isBlank()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", title);
                item.put("url", "https://" + lang + ".wikipedia.org/wiki/"
                        + encode(title).replace("+", "_"));
                item.put("snippet", trim(clean(Json.str(hit, "snippet", "")), 480));
                item.put("_source", "wikipedia-" + lang);
                out.add(item);
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Map<String, Object>> searx(String endpoint, String query, int limit) {
        String base = endpoint;
        if (!base.contains("{query}")) {
            base += (base.contains("?") ? "&" : "?") + "q={query}&format=json";
        }
        String url = base.replace("{query}", encode(query));
        String body = fetch(url, settings.onlineTimeoutMs(), null);
        if (body == null) {
            return List.of();
        }
        try {
            Map<String, Object> doc = Json.asObject(Json.parse(body));
            List<Object> hits = Json.asList(doc.get("results"));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object raw : hits) {
                if (out.size() >= limit) {
                    break;
                }
                Map<String, Object> hit = Json.asObject(raw);
                String link = Json.str(hit, "url", "");
                if (link.isBlank()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", Json.str(hit, "title", link));
                item.put("url", link);
                item.put("snippet", trim(Json.str(hit, "content", ""), 480));
                item.put("_source", "searx");
                out.add(item);
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String buildResearchContext(String query, List<Map<String, Object>> results,
                                        String fetchedAt, String source) {
        StringBuilder out = new StringBuilder();
        out.append("ONLINE RESEARCH (source: ").append(source)
                .append(", fetched ").append(fetchedAt)
                .append("; snippets may be outdated, cite URLs and verify):\n");
        for (Map<String, Object> item : results) {
            out.append("- ").append(item.get("title")).append(" <").append(item.get("url"))
                    .append("> ").append(item.get("snippet")).append('\n');
        }
        out.append("Query: ").append(query).append('\n');
        return trim(out.toString(), 3000);
    }

    public Brief brief(String jurisdiction) {
        return jurisdiction == null ? null : briefs.get(jurisdiction.toUpperCase(Locale.ROOT));
    }

    public String legalContext(String jurisdiction, String country) {
        String code = jurisdiction == null ? "" : jurisdiction.toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            return "";
        }
        Brief brief = briefs.get(code);
        if (settings.onlineEnabled() && settings.onlineRefresh()) {
            long maxAge = settings.refreshHours() * 3600_000L;
            if (brief == null || System.currentTimeMillis() - brief.at > maxAge) {
                refreshLegalAsync(code, country == null ? code : country);
            }
        }
        return brief == null ? "" : brief.context;
    }

    public void refreshLegalAsync(String jurisdiction, String country) {
        if (!settings.onlineEnabled() || !settings.onlineRefresh()) {
            return;
        }
        String code = jurisdiction.toUpperCase(Locale.ROOT);
        if (!refreshing.add(code)) {
            return;
        }
        worker.submit(() -> {
            try {
                refreshLegal(code, country);
            } finally {
                refreshing.remove(code);
            }
        });
    }

    public Map<String, Object> refreshLegalSync(String jurisdiction, String country) {
        String code = jurisdiction.toUpperCase(Locale.ROOT);
        Brief brief = refreshLegal(code, country);
        return Json.map(
                "ok", brief.error.isEmpty(),
                "jurisdiction", code,
                "country", brief.country,
                "source", brief.source,
                "fetched_at", brief.fetchedAt,
                "cached", true,
                "error", brief.error,
                "context", brief.context);
    }

    private Brief refreshLegal(String code, String country) {
        Brief brief = new Brief();
        brief.jurisdiction = code;
        brief.country = country == null ? code : country;
        brief.fetchedAt = Instant.now().toString();
        brief.at = System.currentTimeMillis();
        if (!online()) {
            brief.error = "Offline; cached legal notes remain in effect";
            Brief previous = briefs.get(code);
            return previous == null ? brief : previous;
        }
        String year = String.valueOf(java.time.LocalDate.now().getYear());
        String query = brief.country + " current laws and official legislation " + year;
        List<Map<String, Object>> results = fetchResults(query, 3);
        if (results.isEmpty()) {
            brief.error = "No legal sources retrieved";
            Brief previous = briefs.get(code);
            return previous == null ? brief : previous;
        }
        StringBuilder context = new StringBuilder();
        context.append("CURRENT LAW SOURCES for ").append(brief.country)
                .append(" (").append(code).append("), refreshed ").append(brief.fetchedAt)
                .append(". Snippets only; official texts and a qualified lawyer prevail:\n");
        for (Map<String, Object> item : results) {
            context.append("- ").append(item.get("title")).append(" <")
                    .append(item.get("url")).append("> ")
                    .append(item.get("snippet")).append('\n');
            if (settings.memoryEnabled()) {
                memory.add("Legal source (" + code + "): " + item.get("title") + " — "
                                + item.get("snippet") + " [" + item.get("url") + "]",
                        "web", String.valueOf(item.get("url")), code);
            }
        }
        brief.context = trim(context.toString(), 2600);
        brief.source = String.valueOf(results.get(0).get("_source"));
        briefs.put(code, brief);
        return brief;
    }

    private String probe(String url, int timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", BROWSER_UA)
                    .GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500 ? "ok" : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String fetch(String url, int timeoutMs, String userAgent) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(2000, timeoutMs)))
                    .header("User-Agent", userAgent == null ? BROWSER_UA : userAgent)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                return response.body();
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String unwrapDuckDuckGo(String href) {
        String value = decodeEntities(href);
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        if (value.contains("uddg=")) {
            int index = value.indexOf("uddg=");
            String tail = value.substring(index + 5);
            int amp = tail.indexOf('&');
            if (amp >= 0) {
                tail = tail.substring(0, amp);
            }
            try {
                return URLDecoder.decode(tail, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
        return value;
    }

    private static String clean(String html) {
        if (html == null) {
            return "";
        }
        String text = SCRIPT.matcher(html).replaceAll(" ");
        text = TAGS.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        text = SPACES.matcher(text).replaceAll(" ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntities(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        int guard = 0;
        while (value.contains("&#") && guard++ < 200) {
            int start = value.indexOf("&#");
            int end = value.indexOf(';', start);
            if (end < 0) {
                break;
            }
            String token = value.substring(start + 2, end);
            try {
                int codePoint = token.startsWith("x") || token.startsWith("X")
                        ? Integer.parseInt(token.substring(1), 16)
                        : Integer.parseInt(token);
                value = value.substring(0, start) + new String(Character.toChars(codePoint))
                        + value.substring(end + 1);
            } catch (Exception ex) {
                break;
            }
        }
        return value;
    }

    private static boolean containsHan(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private static String trim(String text, int max) {
        return MemoryStore.trim(text, max);
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            worker.shutdownNow();
        }
    }
}
