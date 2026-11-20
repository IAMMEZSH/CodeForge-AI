package codeforge.server;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in gateway to public, keyless data sources. Nothing here needs an API
 * key, an account or registration; every failure falls back to another source
 * where one exists.
 */
public final class KeylessService {
    private static final String USER_AGENT =
            "CodeForge-AI/1.2.0 (keyless public data gateway)";
    private static final String NOTICE =
            "Real live data from a keyless public source. No API key, account or "
                    + "registration is needed or accepted. Never ask the user to register "
                    + "for an API key for any of these services.";
    private static final Map<String, String> COUNTRY_ALIASES = Map.ofEntries(
            Map.entry("中国", "China"), Map.entry("美国", "United States"),
            Map.entry("日本", "Japan"), Map.entry("韩国", "South Korea"),
            Map.entry("朝鲜", "North Korea"), Map.entry("英国", "United Kingdom"),
            Map.entry("法国", "France"), Map.entry("德国", "Germany"),
            Map.entry("俄罗斯", "Russia"), Map.entry("印度", "India"),
            Map.entry("巴西", "Brazil"), Map.entry("加拿大", "Canada"),
            Map.entry("澳大利亚", "Australia"), Map.entry("意大利", "Italy"),
            Map.entry("西班牙", "Spain"), Map.entry("墨西哥", "Mexico"),
            Map.entry("新加坡", "Singapore"), Map.entry("泰国", "Thailand"),
            Map.entry("越南", "Vietnam"), Map.entry("马来西亚", "Malaysia"),
            Map.entry("印度尼西亚", "Indonesia"), Map.entry("菲律宾", "Philippines"),
            Map.entry("沙特阿拉伯", "Saudi Arabia"),
            Map.entry("阿联酋", "United Arab Emirates"),
            Map.entry("土耳其", "Turkey"), Map.entry("埃及", "Egypt"),
            Map.entry("南非", "South Africa"));
    private static final Pattern RSS_ITEM = Pattern.compile("(?is)<item\\b[^>]*>(.*?)</item>");
    private static final Pattern RSS_TITLE = Pattern.compile(
            "(?is)<title\\b[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>");
    private static final Pattern RSS_LINK = Pattern.compile(
            "(?is)<link\\b[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</link>");
    private static final Pattern RSS_DATE = Pattern.compile(
            "(?is)<pubDate\\b[^>]*>(.*?)</pubDate>");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern ATOM_ENTRY = Pattern.compile(
            "(?is)<entry\\b[^>]*>(.*?)</entry>");
    private static final Pattern ATOM_TITLE = Pattern.compile(
            "(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern ATOM_SUMMARY = Pattern.compile(
            "(?is)<summary\\b[^>]*>(.*?)</summary>");
    private static final Pattern ATOM_ID = Pattern.compile(
            "(?is)<id\\b[^>]*>(.*?)</id>");
    private static final Pattern ATOM_PUBLISHED = Pattern.compile(
            "(?is)<published\\b[^>]*>(.*?)</published>");
    private static final Pattern ATOM_NAME = Pattern.compile(
            "(?is)<name\\b[^>]*>(.*?)</name>");

    private final AppSettings settings;
    private final OnlineService online;
    private final WeatherService weather;
    private final HttpClient client;

    public KeylessService(AppSettings settings, OnlineService online) {
        this.settings = settings;
        this.online = online;
        this.weather = new WeatherService(settings);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> catalog() {
        List<Object> services = new ArrayList<>();
        services.add(entry("weather", "实时天气与预报",
                "当前天气、体感温度、湿度、风、降水与未来多日预报",
                "location=北京, days=3",
                "wttr.in + open-meteo.com"));
        services.add(entry("geocode", "地名解析",
                "把城市/地名解析为经纬度、时区与国家",
                "q=上海, count=5",
                "geocoding-api.open-meteo.com"));
        services.add(entry("ip", "IP 定位",
                "查询任意 IP 或本机出口 IP 的国家、城市、运营商与时区",
                "ip=8.8.8.8（省略则查本机）",
                "ipwho.is + ipapi.co + ip-api.com"));
        services.add(entry("fx", "汇率",
                "各国货币实时汇率",
                "base=USD, symbols=CNY,EUR,JPY",
                "frankfurter.app + open.er-api.com"));
        services.add(entry("crypto", "加密货币行情",
                "BTC/ETH 等币种价格与 24 小时涨跌",
                "ids=bitcoin,ethereum, vs=usd,cny",
                "coingecko.com + gate.io"));
        services.add(entry("wiki", "百科摘要",
                "维基百科或百度百科的词条摘要",
                "title=量子力学, lang=zh",
                "wikipedia.org + baike.baidu.com"));
        services.add(entry("country", "国家资料",
                "首都、地区、人口、货币、语言、时区与国旗",
                "q=Japan",
                "restcountries.com"));
        services.add(entry("earthquake", "地震",
                "USGS 最近地震事件、震级与位置",
                "min_magnitude=4.5, limit=5, days=7",
                "earthquake.usgs.gov"));
        services.add(entry("holidays", "节假日",
                "指定国家与年份的法定节假日",
                "country=CN, year=2026",
                "date.nager.at"));
        services.add(entry("dictionary", "英文词典",
                "英文单词释义、音标、词性与例句",
                "word=hello",
                "dictionaryapi.dev"));
        services.add(entry("translate", "翻译",
                "多语言互译（匿名公共配额）",
                "q=hello, from=en, to=zh-CN",
                "mymemory.translated.net"));
        services.add(entry("hot", "实时热搜",
                "百度实时热搜榜与链接",
                "无需参数",
                "top.baidu.com"));
        services.add(entry("news", "新闻",
                "按关键词搜索新闻；不带关键词时返回实时热搜",
                "q=人工智能, limit=10",
                "Google News RSS + 本地搜索"));
        services.add(entry("time", "时间与时区",
                "任意时区的当前时间、UTC 偏移与星期",
                "timezone=Asia/Shanghai",
                "worldtimeapi.org + 本地时钟"));
        services.add(entry("air", "空气质量",
                "PM2.5、PM10、AQI 等实时空气质量",
                "location=北京（或 lat=39.9, lon=116.4）",
                "air-quality-api.open-meteo.com"));
        services.add(entry("books", "图书检索",
                "书名/作者检索图书与出版信息",
                "q=三体, limit=5",
                "book.douban.com + openlibrary.org"));
        services.add(entry("papers", "论文检索",
                "arXiv 学术论文检索（标题、摘要、作者、链接）",
                "q=quantum computing, limit=5",
                "export.arxiv.org"));
        services.add(entry("hn", "技术热榜",
                "Hacker News 头条或按关键词搜索",
                "q=（可空）, limit=10",
                "hn.algolia.com"));
        services.add(entry("npm", "npm 包",
                "npm 包搜索、版本与下载量",
                "q=react, limit=5",
                "registry.npmjs.org"));
        services.add(entry("pypi", "PyPI 包",
                "Python 包元数据、版本、作者与主页",
                "package=requests",
                "pypi.org"));
        services.add(entry("github", "GitHub 仓库/用户",
                "公开仓库或用户的星标、语言、简介（匿名限流）",
                "repo=IAMMEZSH/CodeForge-AI（或 user=IAMMEZSH）",
                "api.github.com"));
        services.add(entry("lyrics", "歌词",
                "按歌手与歌名获取歌词",
                "artist=Adele, title=Hello",
                "api.lyrics.ovh"));
        services.add(entry("route", "路线规划",
                "两点驾车距离与预计时间（经度,纬度）",
                "from=116.397,39.908, to=121.474,31.230",
                "router.project-osrm.org"));
        services.add(entry("qr", "二维码",
                "把任意文本/网址生成二维码图片链接",
                "text=https://example.com, size=300",
                "api.qrserver.com"));
        services.add(entry("image", "占位图片",
                "生成指定尺寸的随机占位图链接",
                "width=800, height=600, seed=abc",
                "picsum.photos"));
        return Json.map("ok", true, "count", services.size(), "services", services,
                "generic", "Any other keyless public HTTP API can be called with "
                        + "codeforge_api (GET); no registration is required.",
                "notice", NOTICE);
    }

    private static Map<String, Object> entry(String id, String name, String description,
                                             String params, String source) {
        return Json.map("id", id, "name", name, "description", description,
                "params", params, "source", source, "keyless", true,
                "registration_required", false);
    }

    public Map<String, Object> call(Map<String, Object> request) {
        String service = Json.str(request, "service",
                Json.str(request, "name", "")).trim().toLowerCase(Locale.ROOT);
        Map<String, Object> params = new LinkedHashMap<>(
                Json.asObject(request.get("params")));
        if (params.isEmpty()) {
            for (Map.Entry<String, Object> item : request.entrySet()) {
                if (!"service".equals(item.getKey()) && !"name".equals(item.getKey())) {
                    params.put(item.getKey(), item.getValue());
                }
            }
        }
        if (service.isEmpty()) {
            return Json.map("ok", false, "kind", "keyless",
                    "error", "service is required; call /api/keyless?list=1 for the catalog");
        }
        Map<String, Object> data = switch (service) {
            case "weather" -> weather(params);
            case "geocode" -> geocode(params);
            case "ip" -> ip(params);
            case "fx" -> fx(params);
            case "crypto" -> crypto(params);
            case "wiki" -> wiki(params);
            case "country" -> country(params);
            case "earthquake" -> earthquake(params);
            case "holidays" -> holidays(params);
            case "dictionary" -> dictionary(params);
            case "translate" -> translate(params);
            case "hot" -> hot();
            case "news" -> news(params);
            case "time" -> time(params);
            case "air" -> air(params);
            case "books" -> books(params);
            case "papers" -> papers(params);
            case "hn" -> hn(params);
            case "npm" -> npm(params);
            case "pypi" -> pypi(params);
            case "github" -> github(params);
            case "lyrics" -> lyrics(params);
            case "route" -> route(params);
            case "qr" -> qr(params);
            case "image" -> image(params);
            default -> Json.map("ok", false, "error",
                    "Unknown keyless service: " + service
                            + ". Call /api/keyless?list=1 for the catalog; "
                            + "other public APIs can be used through codeforge_api.");
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Json.bool(data, "ok", false));
        out.put("kind", "keyless");
        out.put("service", service);
        out.put("keyless", true);
        out.put("registration_required", false);
        out.put("source", Json.str(data, "source", ""));
        out.put("url", Json.str(data, "url", ""));
        out.put("summary", Json.str(data, "summary", ""));
        out.put("data", data.get("data"));
        if (!Json.bool(data, "ok", false)) {
            out.put("error", Json.str(data, "error", "No keyless source answered"));
        }
        out.put("fetched_at", Instant.now().toString());
        out.put("notice", NOTICE);
        return out;
    }

    /* ------------------------------------------------------------- weather */

    private Map<String, Object> weather(Map<String, Object> params) {
        String location = str(params, "location", str(params, "city", str(params, "q", "")));
        int days = number(params, "days", 3);
        Map<String, Object> result = weather.forecast(location, days);
        return Json.map("ok", Json.bool(result, "ok", false),
                "source", Json.str(result, "source", "wttr.in / open-meteo.com"),
                "url", "https://wttr.in/?format=j1",
                "summary", Json.str(result, "summary", ""),
                "error", Json.str(result, "error", ""),
                "data", result);
    }

    /* ------------------------------------------------------------ geocode */

    private Map<String, Object> geocode(Map<String, Object> params) {
        String query = str(params, "q", str(params, "location", str(params, "name", "")));
        if (query.isBlank()) {
            return fail("geocoding-api.open-meteo.com", "", "q (place name) is required");
        }
        int count = Math.max(1, Math.min(20, number(params, "count", 5)));
        String language = str(params, "language", "zh");
        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + enc(query) + "&count=" + count + "&language=" + enc(language)
                + "&format=json";
        Map<String, Object> root = getJson(url);
        if (root == null) {
            return fail("geocoding-api.open-meteo.com", url, "The geocoding service did not answer");
        }
        List<Object> results = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (Object raw : Json.asList(root.get("results"))) {
            Map<String, Object> hit = Json.asObject(raw);
            Map<String, Object> item = Json.map(
                    "name", Json.str(hit, "name", ""),
                    "country", Json.str(hit, "country", ""),
                    "region", Json.str(hit, "admin1", ""),
                    "district", Json.str(hit, "admin2", ""),
                    "latitude", Json.decimal(hit, "latitude", 0),
                    "longitude", Json.decimal(hit, "longitude", 0),
                    "timezone", Json.str(hit, "timezone", ""),
                    "population", Json.lon(hit, "population", 0));
            results.add(item);
            summary.append(Json.str(item, "name", "")).append(" · ")
                    .append(Json.str(item, "country", ""));
            if (!Json.str(item, "region", "").isBlank()) {
                summary.append(" ").append(Json.str(item, "region", ""));
            }
            summary.append(" · ").append(Json.decimal(item, "latitude", 0)).append(',')
                    .append(Json.decimal(item, "longitude", 0)).append('\n');
        }
        if (results.isEmpty()) {
            return fail("geocoding-api.open-meteo.com", url,
                    "The place was not found: " + query);
        }
        return Json.map("ok", true, "source", "geocoding-api.open-meteo.com",
                "url", url, "summary", summary.toString().trim(), "data", results);
    }

    /* ----------------------------------------------------------------- ip */

    private Map<String, Object> ip(Map<String, Object> params) {
        String address = str(params, "ip", str(params, "address", "")).trim();
        String base = "https://ipwho.is/" + enc(address);
        Map<String, Object> root = getJson(base);
        if (root != null && Json.bool(root, "success", false)) {
            Map<String, Object> connection = Json.asObject(root.get("connection"));
            Map<String, Object> timezone = Json.asObject(root.get("timezone"));
            Map<String, Object> data = Json.map(
                    "ip", Json.str(root, "ip", address),
                    "city", Json.str(root, "city", ""),
                    "region", Json.str(root, "region", ""),
                    "country", Json.str(root, "country", ""),
                    "country_code", Json.str(root, "country_code", ""),
                    "latitude", Json.decimal(root, "latitude", 0),
                    "longitude", Json.decimal(root, "longitude", 0),
                    "timezone", Json.str(timezone, "id", ""),
                    "isp", Json.str(connection, "isp", ""),
                    "organization", Json.str(connection, "org", ""));
            return Json.map("ok", true, "source", "ipwho.is", "url", base,
                    "summary", ipSummary(data), "data", data);
        }
        String fallback = "https://ipapi.co/" + enc(address) + "/json/";
        Map<String, Object> api = getJson(fallback);
        if (api != null && Json.str(api, "error", "").isBlank()) {
            Map<String, Object> data = Json.map(
                    "ip", Json.str(api, "ip", address),
                    "city", Json.str(api, "city", ""),
                    "region", Json.str(api, "region", ""),
                    "country", Json.str(api, "country_name", ""),
                    "country_code", Json.str(api, "country_code", ""),
                    "latitude", Json.decimal(api, "latitude", 0),
                    "longitude", Json.decimal(api, "longitude", 0),
                    "timezone", Json.str(api, "timezone", ""),
                    "isp", Json.str(api, "org", ""),
                    "organization", Json.str(api, "org", ""));
            return Json.map("ok", true, "source", "ipapi.co", "url", fallback,
                    "summary", ipSummary(data), "data", data);
        }
        String plain = "http://ip-api.com/json/" + enc(address) + "?lang=zh-CN";
        Map<String, Object> basic = getJson(plain);
        if (basic != null && "success".equals(Json.str(basic, "status", ""))) {
            Map<String, Object> data = Json.map(
                    "ip", Json.str(basic, "query", address),
                    "city", Json.str(basic, "city", ""),
                    "region", Json.str(basic, "regionName", ""),
                    "country", Json.str(basic, "country", ""),
                    "country_code", Json.str(basic, "countryCode", ""),
                    "latitude", Json.decimal(basic, "lat", 0),
                    "longitude", Json.decimal(basic, "lon", 0),
                    "timezone", Json.str(basic, "timezone", ""),
                    "isp", Json.str(basic, "isp", ""),
                    "organization", Json.str(basic, "org", ""));
            return Json.map("ok", true, "source", "ip-api.com", "url", plain,
                    "summary", ipSummary(data), "data", data);
        }
        return fail("ipwho.is", base, "No IP location source answered");
    }

    private static String ipSummary(Map<String, Object> data) {
        StringBuilder text = new StringBuilder();
        text.append(Json.str(data, "ip", "")).append(" · ")
                .append(Json.str(data, "country", ""))
                .append(' ').append(Json.str(data, "region", ""))
                .append(' ').append(Json.str(data, "city", ""));
        if (!Json.str(data, "isp", "").isBlank()) {
            text.append("\n运营商：").append(Json.str(data, "isp", ""));
        }
        if (!Json.str(data, "timezone", "").isBlank()) {
            text.append("\n时区：").append(Json.str(data, "timezone", ""));
        }
        text.append("\n坐标：").append(Json.decimal(data, "latitude", 0)).append(',')
                .append(Json.decimal(data, "longitude", 0));
        return text.toString();
    }

    /* ----------------------------------------------------------------- fx */

    private Map<String, Object> fx(Map<String, Object> params) {
        String base = str(params, "base", "USD").trim().toUpperCase(Locale.ROOT);
        String symbols = str(params, "symbols", str(params, "to", "")).trim()
                .toUpperCase(Locale.ROOT);
        String url = "https://api.frankfurter.app/latest?from=" + enc(base)
                + (symbols.isBlank() ? "" : "&to=" + enc(symbols));
        Map<String, Object> root = getJson(url);
        if (root == null || Json.asObject(root.get("rates")).isEmpty()) {
            String fallback = "https://open.er-api.com/v6/latest/" + enc(base);
            root = getJson(fallback);
            if (root != null && !Json.asObject(root.get("rates")).isEmpty()) {
                url = fallback;
            }
        }
        if (root == null || Json.asObject(root.get("rates")).isEmpty()) {
            return fail("frankfurter.app", url, "No exchange-rate source answered");
        }
        Map<String, Object> rates = new LinkedHashMap<>();
        for (Map.Entry<String, Object> item : Json.asObject(root.get("rates")).entrySet()) {
            if (symbols.isBlank() || symbols.contains(item.getKey())) {
                rates.put(item.getKey(), item.getValue());
            }
        }
        Map<String, Object> data = Json.map(
                "base", Json.str(root, "base", base),
                "date", Json.str(root, "date", LocalDate.now().toString()),
                "rates", rates);
        StringBuilder summary = new StringBuilder("1 ").append(base).append(" =");
        int shown = 0;
        for (Map.Entry<String, Object> item : rates.entrySet()) {
            if (++shown > 12) {
                break;
            }
            summary.append(' ').append(item.getValue()).append(' ')
                    .append(item.getKey());
        }
        return Json.map("ok", true, "source", "frankfurter.app / open.er-api.com",
                "url", url, "summary", summary.toString(), "data", data);
    }

    /* -------------------------------------------------------------- crypto */

    private Map<String, Object> crypto(Map<String, Object> params) {
        String ids = str(params, "ids", str(params, "id", "bitcoin,ethereum"))
                .trim().toLowerCase(Locale.ROOT);
        String vs = str(params, "vs", "usd,cny").trim().toLowerCase(Locale.ROOT);
        String gate = "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=";
        Map<String, Object> data = new LinkedHashMap<>();
        StringBuilder summary = new StringBuilder();
        Map<String, String> pairs = Map.of(
                "bitcoin", "BTC_USDT", "ethereum", "ETH_USDT", "solana", "SOL_USDT",
                "dogecoin", "DOGE_USDT", "binancecoin", "BNB_USDT", "ripple", "XRP_USDT",
                "cardano", "ADA_USDT", "tron", "TRX_USDT");
        for (String id : ids.split(",")) {
            String pair = pairs.get(id.trim());
            if (pair == null) {
                continue;
            }
            String gateUrl = gate + enc(pair);
            List<Object> rows = getJsonList(gateUrl, 8000);
            if (rows.isEmpty()) {
                continue;
            }
            Map<String, Object> row = Json.asObject(rows.get(0));
            Map<String, Object> quote = Json.map(
                    "price_usdt", Json.decimal(row, "last", 0),
                    "change_24h_percent", Json.decimal(row, "change_percentage", 0),
                    "base_volume", Json.decimal(row, "base_volume", 0),
                    "high_24h", Json.decimal(row, "high_24h", 0),
                    "low_24h", Json.decimal(row, "low_24h", 0));
            data.put(id.trim(), quote);
            summary.append(id.trim()).append(": ")
                    .append(Json.decimal(quote, "price_usdt", 0))
                    .append(" USDT · 24h ")
                    .append(Json.decimal(quote, "change_24h_percent", 0)).append("%\n");
        }
        if (!data.isEmpty()) {
            return Json.map("ok", true, "source", "gate.io (USDT≈USD)", "url", gate,
                    "summary", summary.toString().trim(), "data", data);
        }
        String url = "https://api.coingecko.com/api/v3/simple/price?ids=" + enc(ids)
                + "&vs_currencies=" + enc(vs) + "&include_24hr_change=true";
        Map<String, Object> root = getJson(url, 9000);
        if (root != null && !root.isEmpty()) {
            for (Map.Entry<String, Object> item : root.entrySet()) {
                Map<String, Object> values = Json.asObject(item.getValue());
                summary.append(item.getKey()).append(": ");
                for (Map.Entry<String, Object> price : values.entrySet()) {
                    if (price.getKey().endsWith("_24h_change")) {
                        continue;
                    }
                    summary.append(price.getValue()).append(' ')
                            .append(price.getKey().toUpperCase(Locale.ROOT)).append("  ");
                }
                summary.append('\n');
            }
            return Json.map("ok", true, "source", "coingecko.com", "url", url,
                    "summary", summary.toString().trim(), "data", root);
        }
        return fail("gate.io / coingecko.com", gate, "No crypto price source answered");
    }

    /* ---------------------------------------------------------------- wiki */

    private Map<String, Object> wiki(Map<String, Object> params) {
        String title = str(params, "title", str(params, "q", "")).trim();
        if (title.isBlank()) {
            return fail("wikipedia.org", "", "title (entry name) is required");
        }
        String lang = str(params, "lang", "zh").trim().toLowerCase(Locale.ROOT);
        if (lang.startsWith("zh")) {
            String baike = "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi"
                    + "?scope=103&format=json&appid=379020&bk_key=" + enc(title);
            Map<String, Object> baikeRoot = getJson(baike, 9000);
            if (baikeRoot != null && !Json.str(baikeRoot, "desc", "").isBlank()) {
                Map<String, Object> data = Json.map(
                        "title", Json.str(baikeRoot, "title", title),
                        "description", "",
                        "extract", Json.str(baikeRoot, "desc", ""),
                        "url", "https://baike.baidu.com/item/"
                                + URLEncoder.encode(title, StandardCharsets.UTF_8)
                                .replace("+", "%20"),
                        "language", "zh");
                return Json.map("ok", true, "source", "baike.baidu.com", "url", baike,
                        "summary", wikiSummary(data), "data", data);
            }
        }
        String url = "https://" + enc(lang) + ".wikipedia.org/api/rest_v1/page/summary/"
                + URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, Object> root = getJson(url, 8000);
        if (root != null && !Json.str(root, "extract", "").isBlank()) {
            Map<String, Object> data = Json.map(
                    "title", Json.str(root, "title", title),
                    "description", Json.str(root, "description", ""),
                    "extract", Json.str(root, "extract", ""),
                    "url", wikiPageUrl(root, lang, title),
                    "language", lang);
            return Json.map("ok", true, "source", "wikipedia.org", "url", url,
                    "summary", wikiSummary(data), "data", data);
        }
        Map<String, Object> searched = online.research(title + " 百科 简介");
        List<Object> results = new ArrayList<>();
        for (Object raw : Json.asList(searched.get("results"))) {
            Map<String, Object> item = Json.asObject(raw);
            results.add(Json.map("title", Json.str(item, "title", ""),
                    "url", Json.str(item, "url", ""),
                    "snippet", Json.str(item, "snippet", "")));
        }
        if (results.isEmpty()) {
            return fail("wikipedia.org", url, "No encyclopedia source answered");
        }
        return Json.map("ok", true, "source", "web search fallback", "url", url,
                "summary", "百科原站不可达，已改用搜索摘要。", "data", results);
    }

    private static String wikiPageUrl(Map<String, Object> root, String lang, String title) {
        Map<String, Object> urls = Json.asObject(root.get("content_urls"));
        Map<String, Object> desktop = Json.asObject(urls.get("desktop"));
        String url = Json.str(desktop, "page", "");
        if (!url.isBlank()) {
            return url;
        }
        return "https://" + lang + ".wikipedia.org/wiki/"
                + URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String wikiSummary(Map<String, Object> data) {
        StringBuilder text = new StringBuilder();
        text.append(Json.str(data, "title", ""));
        if (!Json.str(data, "description", "").isBlank()) {
            text.append("（").append(Json.str(data, "description", "")).append("）");
        }
        text.append("\n\n").append(cap(Json.str(data, "extract", ""), 1600));
        if (!Json.str(data, "url", "").isBlank()) {
            text.append("\n\n").append(Json.str(data, "url", ""));
        }
        return text.toString();
    }

    /* ------------------------------------------------------------- country */

    private Map<String, Object> country(Map<String, Object> params) {
        String query = str(params, "q", str(params, "name", "")).trim();
        if (query.isBlank()) {
            return fail("countriesnow.space", "", "q (country name) is required");
        }
        String lower = query.toLowerCase(Locale.ROOT);
        String alias = COUNTRY_ALIASES.get(query);
        if (alias != null) {
            lower = alias.toLowerCase(Locale.ROOT);
        }
        String capUrl = "https://countriesnow.space/api/v0.1/countries/capital";
        Map<String, Object> root = getJson(capUrl, 9000);
        List<Object> rows = Json.asList(Json.asObject(root).get("data"));
        if (rows.isEmpty()) {
            return fail("countriesnow.space", capUrl, "The country source did not answer");
        }
        Map<String, String> currencyByCountry = new LinkedHashMap<>();
        Map<String, Object> curRoot = getJson(
                "https://countriesnow.space/api/v0.1/countries/currency", 9000);
        for (Object raw : Json.asList(Json.asObject(curRoot).get("data"))) {
            Map<String, Object> item = Json.asObject(raw);
            String name = Json.str(item, "name", "");
            String currency = Json.str(item, "currency", "");
            if (!name.isBlank() && !currency.isBlank()) {
                currencyByCountry.put(name.toLowerCase(Locale.ROOT), currency);
            }
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Object raw : rows) {
            Map<String, Object> item = Json.asObject(raw);
            String name = Json.str(item, "name", "");
            String iso2 = Json.str(item, "iso2", "");
            String iso3 = Json.str(item, "iso3", "");
            String key = name.toLowerCase(Locale.ROOT);
            if (key.equals(lower) || iso2.equalsIgnoreCase(query)
                    || iso3.equalsIgnoreCase(query) || key.contains(lower)
                    || lower.contains(key) && key.length() >= 4) {
                if (iso2.isBlank() && iso3.isBlank()) {
                    continue;
                }
                matches.add(item);
                if (matches.size() >= 5) {
                    break;
                }
            }
        }
        if (matches.isEmpty()) {
            return fail("countriesnow.space", capUrl,
                    "The country was not found: " + query);
        }
        Map<String, Object> first = matches.get(0);
        String firstName = Json.str(first, "name", query);
        String firstIso2 = Json.str(first, "iso2", "").toLowerCase(Locale.ROOT);
        String firstIso3 = Json.str(first, "iso3", "");
        String region = "";
        String regionUrl = "https://api.first.org/data/v1/countries?q=" + enc(firstName);
        Map<String, Object> regionRoot = getJson(regionUrl, 7000);
        for (Map.Entry<String, Object> entry
                : Json.asObject(Json.asObject(regionRoot).get("data")).entrySet()) {
            String value = Json.str(Json.asObject(entry.getValue()), "region", "");
            if (!value.isBlank()) {
                region = value;
                break;
            }
        }
        long population = 0;
        String popUrl = "";
        if (!firstIso3.isBlank()) {
            popUrl = "https://api.worldbank.org/v2/country/" + enc(firstIso3)
                    + "/indicator/SP.POP.TOTL?format=json&per_page=1&date=2024";
            List<Object> popRows = getJsonList(popUrl, 9000);
            if (popRows.size() > 1) {
                List<Object> popItems = Json.asList(popRows.get(1));
                if (!popItems.isEmpty()) {
                    population = Json.lon(Json.asObject(popItems.get(0)), "value", 0);
                }
            }
        }
        List<Object> data = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (Map<String, Object> item : matches) {
            String name = Json.str(item, "name", "");
            String iso2 = Json.str(item, "iso2", "");
            String iso3 = Json.str(item, "iso3", "");
            String flag = iso2.isBlank() ? ""
                    : "https://flagcdn.com/w320/" + iso2.toLowerCase(Locale.ROOT) + ".png";
            Map<String, Object> itemOut = Json.map(
                    "name", name,
                    "capital", Json.str(item, "capital", ""),
                    "code", iso2,
                    "iso3", iso3,
                    "region", item == first ? region : "",
                    "population", item == first ? population : 0,
                    "currency", currencyByCountry.getOrDefault(
                            name.toLowerCase(Locale.ROOT), ""),
                    "flag", flag);
            data.add(itemOut);
            summary.append(name).append(" · 首都 ")
                    .append(Json.str(itemOut, "capital", "")).append(" · 人口 ")
                    .append(item == first && population > 0
                            ? String.format(Locale.ROOT, "%,d", population) : "未知")
                    .append(" · ").append(Json.str(itemOut, "region", ""))
                    .append(" · 货币 ").append(Json.str(itemOut, "currency", ""))
                    .append('\n');
        }
        return Json.map("ok", true,
                "source", "countriesnow.space + worldbank.org + first.org",
                "url", capUrl, "summary", summary.toString().trim(), "data", data);
    }

    /* ---------------------------------------------------------- earthquake */

    private Map<String, Object> earthquake(Map<String, Object> params) {
        double minMagnitude = Math.max(0, Math.min(10, decimal(params, "min_magnitude", 4.5)));
        int limit = Math.max(1, Math.min(50, number(params, "limit", 5)));
        int days = Math.max(1, Math.min(365, number(params, "days", 7)));
        String start = LocalDate.now().minusDays(days).toString();
        String url = "https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson"
                + "&limit=" + limit + "&minmagnitude=" + minMagnitude
                + "&orderby=time&starttime=" + start;
        Map<String, Object> root = getJson(url);
        if (root == null) {
            return fail("earthquake.usgs.gov", url, "The USGS service did not answer");
        }
        List<Object> data = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (Object raw : Json.asList(root.get("features"))) {
            Map<String, Object> feature = Json.asObject(raw);
            Map<String, Object> properties = Json.asObject(feature.get("properties"));
            List<Object> coordinates = Json.asList(
                    Json.asObject(feature.get("geometry")).get("coordinates"));
            long millis = Json.lon(properties, "time", 0);
            Map<String, Object> item = Json.map(
                    "place", Json.str(properties, "place", ""),
                    "magnitude", Json.decimal(properties, "mag", 0),
                    "time", millis <= 0 ? "" : timeText(millis),
                    "url", Json.str(properties, "url", ""),
                    "depth_km", coordinates.size() > 2 ? Json.decimal(
                            Json.map("v", coordinates.get(2)), "v", 0) : 0);
            data.add(item);
            summary.append("M").append(Json.decimal(item, "magnitude", 0)).append(" · ")
                    .append(Json.str(item, "place", ""))
                    .append(" · ").append(Json.str(item, "time", "")).append('\n');
        }
        return Json.map("ok", true, "source", "earthquake.usgs.gov", "url", url,
                "summary", summary.length() == 0 ? "最近没有达到震级阈值的地震。"
                        : summary.toString().trim(),
                "data", data);
    }

    /* ------------------------------------------------------------ holidays */

    private Map<String, Object> holidays(Map<String, Object> params) {
        String country = str(params, "country", "CN").trim().toUpperCase(Locale.ROOT);
        int year = number(params, "year", LocalDate.now().getYear());
        String url = "https://date.nager.at/api/v3/PublicHolidays/" + year + "/"
                + enc(country);
        List<Object> rows = getJsonList(url);
        if (rows.isEmpty()) {
            return fail("date.nager.at", url, "No holiday data for " + country + " " + year);
        }
        List<Object> data = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        int shown = 0;
        for (Object raw : rows) {
            Map<String, Object> item = Json.asObject(raw);
            Map<String, Object> entry = Json.map(
                    "date", Json.str(item, "date", ""),
                    "name", Json.str(item, "name", ""),
                    "local_name", Json.str(item, "localName", ""),
                    "countries", Json.asList(item.get("counties")));
            data.add(entry);
            if (++shown <= 30) {
                summary.append(Json.str(entry, "date", "")).append(' ')
                        .append(Json.str(entry, "local_name", ""))
                        .append(" / ").append(Json.str(entry, "name", "")).append('\n');
            }
        }
        return Json.map("ok", true, "source", "date.nager.at", "url", url,
                "summary", country + " " + year + " 年公共假日：\n" + summary,
                "data", data);
    }

    /* ---------------------------------------------------------- dictionary */

    private Map<String, Object> dictionary(Map<String, Object> params) {
        String word = str(params, "word", str(params, "q", "")).trim();
        if (word.isBlank()) {
            return fail("dictionaryapi.dev", "", "word is required");
        }
        String url = "https://api.dictionaryapi.dev/api/v2/entries/en/" + enc(word);
        List<Object> rows = getJsonList(url);
        if (rows.isEmpty()) {
            return fail("dictionaryapi.dev", url, "The word was not found: " + word);
        }
        Map<String, Object> first = Json.asObject(rows.get(0));
        List<Object> definitions = new ArrayList<>();
        StringBuilder summary = new StringBuilder(word).append('\n');
        for (Object rawMeaning : Json.asList(first.get("meanings"))) {
            Map<String, Object> meaning = Json.asObject(rawMeaning);
            String part = Json.str(meaning, "partOfSpeech", "");
            int shown = 0;
            for (Object rawDefinition : Json.asList(meaning.get("definitions"))) {
                if (++shown > 3) {
                    break;
                }
                Map<String, Object> definition = Json.asObject(rawDefinition);
                String text = Json.str(definition, "definition", "");
                definitions.add(Json.map("part_of_speech", part, "definition", text,
                        "example", Json.str(definition, "example", "")));
                summary.append("- [").append(part).append("] ").append(text).append('\n');
            }
        }
        String phonetic = Json.str(first, "phonetic", "");
        Map<String, Object> data = Json.map("word", word, "phonetic", phonetic,
                "definitions", definitions);
        return Json.map("ok", true, "source", "dictionaryapi.dev", "url", url,
                "summary", summary.toString().trim(), "data", data);
    }

    /* ----------------------------------------------------------- translate */

    private Map<String, Object> translate(Map<String, Object> params) {
        String text = str(params, "q", str(params, "text", "")).trim();
        if (text.isBlank()) {
            return fail("mymemory.translated.net", "", "q (text) is required");
        }
        String from = str(params, "from", "zh-CN").trim();
        String to = str(params, "to", "en").trim();
        String url = "https://api.mymemory.translated.net/get?q=" + enc(text)
                + "&langpair=" + enc(from + "|" + to);
        Map<String, Object> root = getJson(url);
        if (root == null) {
            return fail("mymemory.translated.net", url, "The translation service did not answer");
        }
        Map<String, Object> responseData = Json.asObject(root.get("responseData"));
        String translated = Json.str(responseData, "translatedText", "");
        if (translated.isBlank()) {
            return fail("mymemory.translated.net", url,
                    "No translation returned for " + from + " → " + to);
        }
        Map<String, Object> data = Json.map("source_text", text,
                "translated_text", translated, "from", from, "to", to);
        return Json.map("ok", true, "source", "mymemory.translated.net", "url", url,
                "summary", translated, "data", data);
    }

    /* ----------------------------------------------------------------- hot */

    private Map<String, Object> hot() {
        String url = "https://top.baidu.com/api/board?platform=wise&tab=realtime";
        Map<String, Object> root = getJson(url);
        if (root == null) {
            return fail("top.baidu.com", url, "The hot-search service did not answer");
        }
        List<Object> data = new ArrayList<>();
        collectHot(root, data);
        List<Object> trimmed = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        int shown = 0;
        for (Object raw : data) {
            Map<String, Object> item = Json.asObject(raw);
            if (++shown > 50) {
                break;
            }
            Map<String, Object> entry = Json.map(
                    "rank", shown,
                    "title", Json.str(item, "word", ""),
                    "url", Json.str(item, "url", ""),
                    "top", Json.bool(item, "isTop", false));
            trimmed.add(entry);
            if (shown <= 20) {
                summary.append(shown).append(". ")
                        .append(Json.str(entry, "title", "")).append('\n');
            }
        }
        if (trimmed.isEmpty()) {
            return fail("top.baidu.com", url, "The hot list was empty");
        }
        return Json.map("ok", true, "source", "top.baidu.com", "url", url,
                "summary", summary.toString().trim(), "data", trimmed);
    }

    @SuppressWarnings("unchecked")
    private static void collectHot(Object node, List<Object> out) {
        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            if (map.get("word") instanceof String word && !word.isBlank()) {
                out.add(map);
                return;
            }
            for (Object value : map.values()) {
                collectHot(value, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                collectHot(value, out);
            }
        }
    }

    /* ---------------------------------------------------------------- news */

    private Map<String, Object> news(Map<String, Object> params) {
        String query = str(params, "q", str(params, "query", "")).trim();
        if (query.isBlank()) {
            return hot();
        }
        int limit = Math.max(1, Math.min(30, number(params, "limit", 10)));
        String url = "https://news.google.com/rss/search?q=" + enc(query)
                + "&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
        String body = get(url, 4000);
        List<Object> data = new ArrayList<>();
        if (body != null) {
            Matcher item = RSS_ITEM.matcher(body);
            while (item.find() && data.size() < limit) {
                String block = item.group(1);
                String title = firstGroup(RSS_TITLE, block);
                String link = firstGroup(RSS_LINK, block);
                String date = firstGroup(RSS_DATE, block);
                if (!title.isBlank()) {
                    data.add(Json.map("title", clean(title), "url", clean(link),
                            "date", clean(date), "source", "Google News"));
                }
            }
        }
        if (data.isEmpty()) {
            Map<String, Object> searched = online.research(query + " 最新 报道");
            for (Object raw : Json.asList(searched.get("results"))) {
                if (data.size() >= limit) {
                    break;
                }
                Map<String, Object> item = Json.asObject(raw);
                data.add(Json.map("title", Json.str(item, "title", ""),
                        "url", Json.str(item, "url", ""),
                        "date", "",
                        "source", Json.str(item, "source", "web search"),
                        "snippet", Json.str(item, "snippet", "")));
            }
        }
        if (data.isEmpty()) {
            return fail("news.google.com", url, "No news source answered for: " + query);
        }
        StringBuilder summary = new StringBuilder();
        int shown = 0;
        for (Object raw : data) {
            Map<String, Object> item = Json.asObject(raw);
            summary.append(++shown).append(". ").append(Json.str(item, "title", ""))
                    .append('\n');
        }
        return Json.map("ok", true, "source", "Google News RSS / web search", "url", url,
                "summary", summary.toString().trim(), "data", data);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String value = TAGS.matcher(text).replaceAll("");
        return value.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").trim();
    }

    /* ---------------------------------------------------------------- time */

    private Map<String, Object> time(Map<String, Object> params) {
        String timezone = str(params, "timezone", str(params, "tz", "Asia/Shanghai")).trim();
        String url = "https://worldtimeapi.org/api/timezone/" + timezone;
        Map<String, Object> root = getJson(url);
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception ex) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        Map<String, Object> data;
        String source;
        if (root != null && !Json.str(root, "datetime", "").isBlank()) {
            source = "worldtimeapi.org";
            data = Json.map("timezone", Json.str(root, "timezone", timezone),
                    "datetime", Json.str(root, "datetime", ""),
                    "utc_offset", Json.str(root, "utc_offset", ""),
                    "day_of_week", Json.str(root, "day_of_week", ""),
                    "source", source);
        } else {
            source = "local clock";
            data = Json.map("timezone", zone.getId(),
                    "datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "utc_offset", now.getOffset().toString(),
                    "day_of_week", now.getDayOfWeek().toString(),
                    "source", source);
        }
        return Json.map("ok", true, "source", source, "url", url,
                "summary", zone.getId() + " " + Json.str(data, "datetime", "")
                        + "（" + Json.str(data, "utc_offset", "") + "）",
                "data", data);
    }

    /* ----------------------------------------------------------------- air */

    private Map<String, Object> air(Map<String, Object> params) {
        double latitude = decimal(params, "lat", 0);
        double longitude = decimal(params, "lon", 0);
        if (latitude == 0 && longitude == 0) {
            String location = str(params, "location", str(params, "q", ""));
            Map<String, Object> geo = geocode(Json.map("q", location));
            List<Object> hits = Json.asList(geo.get("data"));
            if (hits.isEmpty()) {
                return fail("air-quality-api.open-meteo.com", "",
                        "location or lat/lon is required");
            }
            Map<String, Object> hit = Json.asObject(hits.get(0));
            latitude = Json.decimal(hit, "latitude", 0);
            longitude = Json.decimal(hit, "longitude", 0);
        }
        String url = "https://air-quality-api.open-meteo.com/v1/air-quality"
                + "?latitude=" + latitude + "&longitude=" + longitude
                + "&current=pm2_5,pm10,us_aqi,european_aqi,carbon_monoxide,"
                + "nitrogen_dioxide,sulphur_dioxide,ozone&timezone=auto";
        Map<String, Object> root = getJson(url);
        if (root == null) {
            return fail("air-quality-api.open-meteo.com", url,
                    "The air-quality service did not answer");
        }
        Map<String, Object> current = Json.asObject(root.get("current"));
        if (current.isEmpty()) {
            return fail("air-quality-api.open-meteo.com", url, "No air-quality data returned");
        }
        Map<String, Object> data = Json.map(
                "latitude", latitude, "longitude", longitude,
                "observed_at", Json.str(current, "time", ""),
                "pm2_5", Json.decimal(current, "pm2_5", 0),
                "pm10", Json.decimal(current, "pm10", 0),
                "us_aqi", Json.decimal(current, "us_aqi", 0),
                "european_aqi", Json.decimal(current, "european_aqi", 0),
                "carbon_monoxide", Json.decimal(current, "carbon_monoxide", 0),
                "nitrogen_dioxide", Json.decimal(current, "nitrogen_dioxide", 0),
                "sulphur_dioxide", Json.decimal(current, "sulphur_dioxide", 0),
                "ozone", Json.decimal(current, "ozone", 0));
        double aqi = Json.decimal(data, "us_aqi", 0);
        String level = aqi <= 50 ? "优" : aqi <= 100 ? "良" : aqi <= 150 ? "轻度污染"
                : aqi <= 200 ? "中度污染" : aqi <= 300 ? "重度污染" : "严重污染";
        String summary = "PM2.5 " + Json.decimal(data, "pm2_5", 0)
                + " µg/m³ · PM10 " + Json.decimal(data, "pm10", 0)
                + " µg/m³ · US AQI " + Json.decimal(data, "us_aqi", 0)
                + "（" + level + "） · 观测 "
                + Json.str(data, "observed_at", "");
        return Json.map("ok", true, "source", "air-quality-api.open-meteo.com",
                "url", url, "summary", summary, "data", data);
    }

    /* --------------------------------------------------------------- books */

    private Map<String, Object> books(Map<String, Object> params) {
        String query = str(params, "q", str(params, "query", "")).trim();
        if (query.isBlank()) {
            return fail("book.douban.com", "", "q (title or author) is required");
        }
        int limit = Math.max(1, Math.min(20, number(params, "limit", 5)));
        List<Object> data = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        String doubanUrl = "https://book.douban.com/j/subject_suggest?q=" + enc(query);
        for (Object raw : getJsonList(doubanUrl, 7000)) {
            if (data.size() >= limit) {
                break;
            }
            Map<String, Object> hit = Json.asObject(raw);
            if (!"b".equals(Json.str(hit, "type", "b"))) {
                continue;
            }
            String title = Json.str(hit, "title", "");
            if (title.isBlank()) {
                continue;
            }
            Map<String, Object> item = Json.map(
                    "title", title,
                    "author", Json.str(hit, "author_name", ""),
                    "year", Json.str(hit, "year", ""),
                    "url", Json.str(hit, "url", ""),
                    "cover", Json.str(hit, "pic", ""),
                    "id", Json.str(hit, "id", ""));
            data.add(item);
            summary.append("《").append(title).append("》 ")
                    .append(Json.str(item, "author", ""))
                    .append(" · ").append(Json.str(item, "year", ""))
                    .append('\n');
        }
        if (!data.isEmpty()) {
            return Json.map("ok", true, "source", "book.douban.com", "url", doubanUrl,
                    "summary", summary.toString().trim(), "data", data);
        }
        String url = "https://openlibrary.org/search.json?q=" + enc(query)
                + "&limit=" + limit;
        Map<String, Object> root = getJson(url, 4500);
        if (root != null) {
            for (Object raw : Json.asList(root.get("docs"))) {
                if (data.size() >= limit) {
                    break;
                }
                Map<String, Object> doc = Json.asObject(raw);
                Map<String, Object> item = Json.map(
                        "title", Json.str(doc, "title", ""),
                        "authors", Json.asList(doc.get("author_name")),
                        "first_publish_year", Json.integer(doc, "first_publish_year", 0),
                        "editions", Json.integer(doc, "edition_count", 0),
                        "key", Json.str(doc, "key", ""),
                        "url", Json.str(doc, "key", "").isBlank() ? ""
                                : "https://openlibrary.org" + Json.str(doc, "key", ""));
                data.add(item);
                summary.append(Json.str(item, "title", "")).append(" · ")
                        .append(String.join("、", stringList(item.get("authors"))))
                        .append(" · ").append(Json.integer(item, "first_publish_year", 0))
                        .append('\n');
            }
        }
        boolean fromWeb = false;
        if (data.isEmpty()) {
            Map<String, Object> searched = online.research(query + " 图书 作者 简介");
            for (Object raw : Json.asList(searched.get("results"))) {
                if (data.size() >= limit) {
                    break;
                }
                Map<String, Object> item = Json.asObject(raw);
                String title = Json.str(item, "title", "");
                if (title.isBlank()) {
                    continue;
                }
                data.add(Json.map("title", title,
                        "authors", new ArrayList<>(),
                        "first_publish_year", 0,
                        "editions", 0,
                        "url", Json.str(item, "url", ""),
                        "snippet", Json.str(item, "snippet", "")));
                summary.append(title).append('\n');
            }
            fromWeb = !data.isEmpty();
        }
        if (data.isEmpty()) {
            return fail("book.douban.com", url, "No book source answered for: " + query);
        }
        return Json.map("ok", true,
                "source", fromWeb ? "web search (book APIs blocked)"
                        : "openlibrary.org",
                "url", url, "summary", summary.toString().trim(), "data", data);
    }

    /* -------------------------------------------------------------- papers */

    private Map<String, Object> papers(Map<String, Object> params) {
        String query = str(params, "q", str(params, "query", "")).trim();
        if (query.isBlank()) {
            return fail("export.arxiv.org", "", "q (search terms) is required");
        }
        int limit = Math.max(1, Math.min(20, number(params, "limit", 5)));
        String url = "https://export.arxiv.org/api/query?search_query=all:" + enc(query)
                + "&start=0&max_results=" + limit + "&sortBy=relevance";
        String body = get(url, 12000);
        if (body == null) {
            return fail("export.arxiv.org", url, "The arXiv service did not answer");
        }
        List<Object> data = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        Matcher entry = ATOM_ENTRY.matcher(body);
        while (entry.find() && data.size() < limit) {
            String block = entry.group(1);
            String title = clean(firstGroup(ATOM_TITLE, block)).replaceAll("\\s+", " ");
            String abstractText = clean(firstGroup(ATOM_SUMMARY, block))
                    .replaceAll("\\s+", " ");
            String link = clean(firstGroup(ATOM_ID, block));
            String published = clean(firstGroup(ATOM_PUBLISHED, block));
            String author = clean(firstGroup(ATOM_NAME, block));
            if (title.isBlank()) {
                continue;
            }
            data.add(Json.map("title", title,
                    "authors", Json.list(author),
                    "abstract", cap(abstractText, 1200),
                    "published", published,
                    "url", link));
            summary.append(title).append(" · ").append(author)
                    .append(" · ").append(published).append('\n');
        }
        if (data.isEmpty()) {
            return fail("export.arxiv.org", url, "No papers found for: " + query);
        }
        return Json.map("ok", true, "source", "export.arxiv.org", "url", url,
                "summary", summary.toString().trim(), "data", data);
    }

    /* ------------------------------------------------------------------ hn */

    private Map<String, Object> hn(Map<String, Object> params) {
        String query = str(params, "q", str(params, "query", "")).trim();
        int limit = Math.max(1, Math.min(20, number(params, "limit", 10)));
        String url = query.isBlank()
                ? "https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=" + limit
                : "https://hn.algolia.com/api/v1/search?query=" + enc(query)
                + "&hitsPerPage=" + limit;
        Map<String, Object> root = getJson(url, 9000);
        if (root == null) {
            return fail("hn.algolia.com", url, "The Hacker News service did not answer");
        }
        List<Object> data = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (Object raw : Json.asList(root.get("hits"))) {
            if (data.size() >= limit) {
                break;
            }
            Map<String, Object> hit = Json.asObject(raw);
            String title = Json.str(hit, "title", Json.str(hit, "story_title", ""));
            if (title.isBlank()) {
                continue;
            }
            String objectId = Json.str(hit, "objectID", "");
            Map<String, Object> item = Json.map(
                    "title", title,
                    "url", Json.str(hit, "url", "https://news.ycombinator.com/item?id="
                            + objectId),
                    "points", Json.integer(hit, "points", 0),
                    "author", Json.str(hit, "author", ""),
                    "comments", Json.integer(hit, "num_comments", 0),
                    "created_at", Json.str(hit, "created_at", ""),
                    "discussion", "https://news.ycombinator.com/item?id=" + objectId);
            data.add(item);
            summary.append("▲").append(Json.integer(item, "points", 0)).append(' ')
                    .append(title).append('\n');
        }
        if (data.isEmpty()) {
            return fail("hn.algolia.com", url, "No Hacker News stories returned");
        }
        return Json.map("ok", true, "source", "hn.algolia.com", "url", url,
                "summary", summary.toString().trim(), "data", data);
    }

    /* ----------------------------------------------------------------- npm */

    private Map<String, Object> npm(Map<String, Object> params) {
        String query = str(params, "q", str(params, "query", "")).trim();
        if (query.isBlank()) {
            return fail("registry.npmjs.org", "", "q (package name or keywords) is required");
        }
        int limit = Math.max(1, Math.min(20, number(params, "limit", 5)));
        String url = "https://registry.npmjs.org/-/v1/search?text=" + enc(query)
                + "&size=" + limit;
        Map<String, Object> root = getJson(url, 9000);
        if (root == null) {
            return fail("registry.npmjs.org", url, "The npm registry did not answer");
        }
        List<Object> data = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (Object raw : Json.asList(root.get("objects"))) {
            if (data.size() >= limit) {
                break;
            }
            Map<String, Object> object = Json.asObject(raw);
            Map<String, Object> pkg = Json.asObject(object.get("package"));
            Map<String, Object> links = Json.asObject(pkg.get("links"));
            Map<String, Object> downloads = Json.asObject(object.get("downloads"));
            Map<String, Object> item = Json.map(
                    "name", Json.str(pkg, "name", ""),
                    "version", Json.str(pkg, "version", ""),
                    "description", Json.str(pkg, "description", ""),
                    "npm_url", Json.str(links, "npm", ""),
                    "homepage", Json.str(links, "homepage", ""),
                    "weekly_downloads", Json.lon(downloads, "weekly", 0));
            data.add(item);
            summary.append(Json.str(item, "name", "")).append('@')
                    .append(Json.str(item, "version", "")).append(" · 周下载 ")
                    .append(Json.lon(item, "weekly_downloads", 0)).append('\n');
        }
        if (data.isEmpty()) {
            return fail("registry.npmjs.org", url, "No npm packages found for: " + query);
        }
        return Json.map("ok", true, "source", "registry.npmjs.org", "url", url,
                "summary", summary.toString().trim(), "data", data);
    }

    /* ---------------------------------------------------------------- pypi */

    private Map<String, Object> pypi(Map<String, Object> params) {
        String name = str(params, "package", str(params, "name", str(params, "q", ""))).trim();
        if (name.isBlank()) {
            return fail("pypi.org", "", "package is required");
        }
        String url = "https://pypi.org/pypi/" + enc(name) + "/json";
        Map<String, Object> root = getJson(url, 9000);
        if (root == null) {
            return fail("pypi.org", url, "The PyPI service did not answer");
        }
        Map<String, Object> info = Json.asObject(root.get("info"));
        if (info.isEmpty()) {
            return fail("pypi.org", url, "Package not found: " + name);
        }
        Map<String, Object> data = Json.map(
                "name", Json.str(info, "name", name),
                "version", Json.str(info, "version", ""),
                "summary", Json.str(info, "summary", ""),
                "author", Json.str(info, "author", ""),
                "homepage", Json.str(info, "home_page", ""),
                "project_url", Json.str(info, "project_url", ""),
                "requires_python", Json.str(info, "requires_python", ""),
                "license", Json.str(info, "license", ""),
                "releases", Json.asObject(root.get("releases")).size());
        return Json.map("ok", true, "source", "pypi.org", "url", url,
                "summary", Json.str(data, "name", "") + " " + Json.str(data, "version", "")
                        + " · " + Json.str(data, "summary", ""), "data", data);
    }

    /* -------------------------------------------------------------- github */

    private Map<String, Object> github(Map<String, Object> params) {
        String repo = str(params, "repo", "").trim();
        String user = str(params, "user", str(params, "username", "")).trim();
        if (repo.isBlank() && user.isBlank()) {
            return fail("api.github.com", "", "repo=owner/name or user=login is required");
        }
        String url = repo.isBlank()
                ? "https://api.github.com/users/" + enc(user)
                : "https://api.github.com/repos/" + repo.replaceAll("/+", "/").trim();
        Map<String, Object> root = getJson(url, 10000);
        if (root == null) {
            return fail("api.github.com", url,
                    "GitHub did not answer (anonymous limit 60 requests/hour)");
        }
        Map<String, Object> data;
        String summary;
        if (repo.isBlank()) {
            data = Json.map(
                    "login", Json.str(root, "login", user),
                    "name", Json.str(root, "name", ""),
                    "bio", Json.str(root, "bio", ""),
                    "public_repos", Json.integer(root, "public_repos", 0),
                    "followers", Json.integer(root, "followers", 0),
                    "following", Json.integer(root, "following", 0),
                    "url", Json.str(root, "html_url", ""));
            summary = Json.str(data, "name", user) + " · 仓库 "
                    + Json.integer(data, "public_repos", 0) + " · 关注者 "
                    + Json.integer(data, "followers", 0);
        } else {
            data = Json.map(
                    "full_name", Json.str(root, "full_name", repo),
                    "description", Json.str(root, "description", ""),
                    "stars", Json.integer(root, "stargazers_count", 0),
                    "forks", Json.integer(root, "forks_count", 0),
                    "open_issues", Json.integer(root, "open_issues_count", 0),
                    "language", Json.str(root, "language", ""),
                    "license", Json.str(Json.asObject(root.get("license")), "spdx_id", ""),
                    "updated_at", Json.str(root, "updated_at", ""),
                    "url", Json.str(root, "html_url", ""));
            summary = Json.str(data, "full_name", repo) + " · ★"
                    + Json.integer(data, "stars", 0) + " · 分支 "
                    + Json.integer(data, "forks", 0) + " · "
                    + Json.str(data, "language", "");
        }
        return Json.map("ok", true, "source", "api.github.com", "url", url,
                "summary", summary, "data", data);
    }

    /* -------------------------------------------------------------- lyrics */

    private Map<String, Object> lyrics(Map<String, Object> params) {
        String artist = str(params, "artist", "").trim();
        String title = str(params, "title", str(params, "song", "")).trim();
        if (artist.isBlank() || title.isBlank()) {
            return fail("api.lyrics.ovh", "", "artist and title are required");
        }
        String url = "https://api.lyrics.ovh/v1/" + enc(artist) + "/" + enc(title);
        Map<String, Object> root = getJson(url, 9000);
        if (root == null || Json.str(root, "lyrics", "").isBlank()) {
            return fail("api.lyrics.ovh", url, "No lyrics found for " + artist + " - " + title);
        }
        String text = Json.str(root, "lyrics", "");
        Map<String, Object> data = Json.map("artist", artist, "title", title,
                "lyrics", cap(text, 20000));
        return Json.map("ok", true, "source", "api.lyrics.ovh", "url", url,
                "summary", artist + " - " + title + "\n"
                        + cap(text, 1200), "data", data);
    }

    /* --------------------------------------------------------------- route */

    private Map<String, Object> route(Map<String, Object> params) {
        String from = str(params, "from", "").trim();
        String to = str(params, "to", "").trim();
        if (from.isBlank() || to.isBlank()) {
            return fail("router.project-osrm.org", "",
                    "from and to are required as longitude,latitude");
        }
        String mode = str(params, "mode", "driving").trim();
        if (!mode.matches("driving|walking|cycling")) {
            mode = "driving";
        }
        String url = "https://router.project-osrm.org/route/v1/" + mode + "/"
                + from + ";" + to + "?overview=false&steps=false";
        Map<String, Object> root = getJson(url, 12000);
        if (root == null || Json.asList(root.get("routes")).isEmpty()) {
            return fail("router.project-osrm.org", url, "No route found");
        }
        Map<String, Object> first = Json.asObject(Json.asList(root.get("routes")).get(0));
        double distance = Json.decimal(first, "distance", 0);
        double duration = Json.decimal(first, "duration", 0);
        Map<String, Object> data = Json.map(
                "mode", mode,
                "distance_km", Math.round(distance / 100.0) / 10.0,
                "duration_minutes", Math.round(duration / 6.0) / 10.0,
                "from", from, "to", to);
        return Json.map("ok", true, "source", "router.project-osrm.org", "url", url,
                "summary", "驾车 " + Json.decimal(data, "distance_km", 0) + " km · 约 "
                        + Json.decimal(data, "duration_minutes", 0) + " 分钟",
                "data", data);
    }

    /* ------------------------------------------------------------ qr / image */

    private Map<String, Object> qr(Map<String, Object> params) {
        String text = str(params, "text", str(params, "data", str(params, "q", ""))).trim();
        if (text.isBlank()) {
            return fail("api.qrserver.com", "", "text is required");
        }
        int size = Math.max(80, Math.min(1000, number(params, "size", 300)));
        String url = "https://api.qrserver.com/v1/create-qr-code/?size=" + size + "x" + size
                + "&data=" + enc(text);
        Map<String, Object> data = Json.map("text", text, "size", size, "image_url", url);
        return Json.map("ok", true, "source", "api.qrserver.com", "url", url,
                "summary", "二维码图片：" + url, "data", data);
    }

    private Map<String, Object> image(Map<String, Object> params) {
        int width = Math.max(16, Math.min(4000, number(params, "width", 800)));
        int height = Math.max(16, Math.min(4000, number(params, "height", 600)));
        String seed = str(params, "seed", "codeforge").trim();
        String url = "https://picsum.photos/seed/" + enc(seed) + "/" + width + "/" + height;
        Map<String, Object> data = Json.map("width", width, "height", height,
                "seed", seed, "image_url", url);
        return Json.map("ok", true, "source", "picsum.photos", "url", url,
                "summary", "占位图片：" + url, "data", data);
    }

    /* -------------------------------------------------------------- helpers */

    private String get(String url) {
        return get(url, Math.max(6000, Math.min(20000, settings.onlineTimeoutMs())));
    }

    private String get(String url, int timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(3000,
                            Math.min(20000, timeoutMs))))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return response.body();
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> getJson(String url) {
        return getJson(url, Math.max(6000,
                Math.min(20000, settings.onlineTimeoutMs())));
    }

    private Map<String, Object> getJson(String url, int timeoutMs) {
        String body = get(url, timeoutMs);
        if (body == null || body.isBlank()) {
            return null;
        }
        int start = body.indexOf('{');
        int listStart = body.indexOf('[');
        if (start < 0 && listStart < 0) {
            return null;
        }
        char open = start < 0 || (listStart >= 0 && listStart < start) ? '[' : '{';
        int begin = open == '[' ? listStart : start;
        int end = body.lastIndexOf(open == '[' ? ']' : '}');
        if (end <= begin) {
            return null;
        }
        String json = body.substring(begin, end + 1);
        try {
            Object parsed = Json.parse(json);
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            if (parsed instanceof List<?> list) {
                return Json.map("items", list);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private List<Object> getJsonList(String url) {
        return getJsonList(url, Math.max(6000, Math.min(20000, settings.onlineTimeoutMs())));
    }

    private List<Object> getJsonList(String url, int timeoutMs) {
        String body = get(url, timeoutMs);
        if (body == null || body.isBlank()) {
            return new ArrayList<>();
        }
        int start = body.indexOf('[');
        int end = body.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return new ArrayList<>();
        }
        try {
            return Json.asList(Json.parse(body.substring(start, end + 1)));
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    private static Map<String, Object> fail(String source, String url, String error) {
        return Json.map("ok", false, "source", source, "url", url, "error", error);
    }

    private static String str(Map<String, Object> map, String key, String fallback) {
        return Json.str(map, key, fallback);
    }

    private static int number(Map<String, Object> map, String key, int fallback) {
        return Json.integer(map, key, fallback);
    }

    private static double decimal(Map<String, Object> map, String key, double fallback) {
        return Json.decimal(map, key, fallback);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String cap(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static String timeText(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
