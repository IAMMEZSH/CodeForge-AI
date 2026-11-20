package codeforge.server;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyless weather: wttr.in first, Open-Meteo (geocoding + forecast) second.
 * Neither source needs an API key, an account or registration.
 */
public final class WeatherService {
    private static final String USER_AGENT =
            "CodeForge-AI/1.2.0 (keyless weather; no registration required)";

    private final AppSettings settings;
    private final HttpClient client;

    public WeatherService(AppSettings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> forecast(String location, int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("kind", "weather");
        out.put("keyless", true);
        out.put("registration_required", false);
        String place = location == null ? "" : location.trim();
        out.put("location_query", place);
        int count = Math.max(1, Math.min(14, days <= 0 ? 3 : days));
        out.put("days", count);
        if (!settings.onlineEnabled()) {
            out.put("error", "Online access is disabled in config.json");
            return out;
        }
        List<Object> attempts = new ArrayList<>();
        Map<String, Object> data = wttr(place, count);
        attempts.add(Json.map("source", "wttr.in",
                "ok", Json.bool(data, "ok", false),
                "error", Json.str(data, "error", "")));
        if (!Json.bool(data, "ok", false)) {
            Map<String, Object> openMeteo = openMeteo(place, count);
            attempts.add(Json.map("source", "open-meteo.com",
                    "ok", Json.bool(openMeteo, "ok", false),
                    "error", Json.str(openMeteo, "error", "")));
            if (Json.bool(openMeteo, "ok", false)) {
                data = openMeteo;
            }
        }
        out.put("sources", attempts);
        if (!Json.bool(data, "ok", false)) {
            out.put("error", "No keyless weather source answered; check the network and retry.");
            out.put("detail", data.get("error"));
            return out;
        }
        out.putAll(data);
        out.put("ok", true);
        out.put("keyless", true);
        out.put("registration_required", false);
        out.put("fetched_at", Instant.now().toString());
        out.put("notice", "This is real live weather from a keyless public source. "
                + "No API key, account or registration is needed or accepted. "
                + "Use only these values and never ask the user for a weather API key.");
        return out;
    }

    /* ------------------------------------------------------------------ wttr.in */

    private Map<String, Object> wttr(String location, int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("source", "wttr.in");
        String city = location.isBlank() ? ""
                : URLEncoder.encode(location, StandardCharsets.UTF_8).replace("+", "%20");
        String body = get("https://wttr.in/" + city + "?format=j1&lang=zh");
        Map<String, Object> root = parseObject(body);
        if (root == null) {
            out.put("error", "wttr.in did not answer");
            return out;
        }
        List<Object> conditions = Json.asList(root.get("current_condition"));
        if (conditions.isEmpty()) {
            out.put("error", "wttr.in returned no current conditions for: "
                    + (location.isBlank() ? "current location" : location));
            return out;
        }
        Map<String, Object> current = Json.asObject(conditions.get(0));
        Map<String, Object> place = Json.asObject(first(root.get("nearest_area")));
        String name = firstValue(place.get("areaName"));
        String region = firstValue(place.get("region"));
        String country = firstValue(place.get("country"));
        Map<String, Object> locationOut = new LinkedHashMap<>();
        locationOut.put("name", location.isBlank()
                ? (name.isBlank() ? "当前位置" : name) : location);
        locationOut.put("resolved_name", name);
        locationOut.put("region", region);
        locationOut.put("country", country);
        locationOut.put("latitude", Json.decimal(place, "latitude", 0));
        locationOut.put("longitude", Json.decimal(place, "longitude", 0));

        String description = firstValue(current.get("lang_zh"));
        if (description.isBlank()) {
            description = firstValue(current.get("weatherDesc"));
        }
        description = translateWeather(description);
        Map<String, Object> currentOut = new LinkedHashMap<>();
        currentOut.put("temperature_c", decimal(current, "temp_C"));
        currentOut.put("feels_like_c", decimal(current, "FeelsLikeC"));
        currentOut.put("humidity_percent", integer(current, "humidity"));
        currentOut.put("precipitation_mm", decimal(current, "precipMM"));
        currentOut.put("weather", description);
        currentOut.put("weather_code", integer(current, "weatherCode"));
        currentOut.put("wind_kmh", decimal(current, "windspeedKmph"));
        currentOut.put("wind_direction", Json.str(current, "winddir16Point", ""));
        currentOut.put("pressure_hpa", decimal(current, "pressure"));
        currentOut.put("visibility_km", decimal(current, "visibility"));
        currentOut.put("uv_index", decimal(current, "uvIndex"));
        currentOut.put("observed_at", Json.str(current, "observation_time", ""));

        List<Object> daily = new ArrayList<>();
        int index = 0;
        for (Object raw : Json.asList(root.get("weather"))) {
            if (++index > days) {
                break;
            }
            Map<String, Object> day = Json.asObject(raw);
            Map<String, Object> noon = noonHour(day);
            String dayDescription = firstValue(noon.get("lang_zh"));
            if (dayDescription.isBlank()) {
                dayDescription = firstValue(noon.get("weatherDesc"));
            }
            Map<String, Object> astronomy = Json.asObject(first(day.get("astronomy")));
            daily.add(Json.map(
                    "date", Json.str(day, "date", ""),
                    "weather", translateWeather(dayDescription),
                    "weather_code", integer(noon, "weatherCode"),
                    "temp_max_c", decimal(day, "maxtempC"),
                    "temp_min_c", decimal(day, "mintempC"),
                    "temp_avg_c", decimal(day, "avgtempC"),
                    "precipitation_chance_percent",
                    integer(noon, "chanceofrain"),
                    "wind_max_kmh", decimal(noon, "windspeedKmph"),
                    "sunrise", Json.str(astronomy, "sunrise", ""),
                    "sunset", Json.str(astronomy, "sunset", "")));
        }

        out.put("location", locationOut);
        out.put("current", currentOut);
        out.put("daily", daily);
        out.put("timezone", "");
        out.put("summary", summary(locationOut, currentOut, daily, "wttr.in（免密钥）"));
        out.put("ok", true);
        return out;
    }

    /* ------------------------------------------------------ Open-Meteo fallback */

    private Map<String, Object> openMeteo(String location, int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("source", "open-meteo.com");
        double latitude;
        double longitude;
        String name = location;
        String region = "";
        String country = "";
        String timezone = "";
        Map<String, Object> coordinates = parseCoordinates(location);
        if (coordinates != null) {
            latitude = Json.decimal(coordinates, "latitude", 0);
            longitude = Json.decimal(coordinates, "longitude", 0);
        } else {
            if (location.isBlank()) {
                out.put("error", "Open-Meteo needs a place name or coordinates");
                return out;
            }
            String query = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(location, StandardCharsets.UTF_8)
                    + "&count=1&language=zh&format=json";
            Map<String, Object> geocode = parseObject(get(query));
            Map<String, Object> hit = Json.asObject(first(
                    geocode == null ? null : geocode.get("results")));
            if (hit.isEmpty()) {
                out.put("error", "The place was not found: " + location);
                return out;
            }
            latitude = Json.decimal(hit, "latitude", 0);
            longitude = Json.decimal(hit, "longitude", 0);
            name = Json.str(hit, "name", location);
            region = Json.str(hit, "admin1", "");
            country = Json.str(hit, "country", "");
            timezone = Json.str(hit, "timezone", "");
        }
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                + "&longitude=" + longitude
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                + "precipitation,weather_code,wind_speed_10m,wind_direction_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "precipitation_probability_max,wind_speed_10m_max,sunrise,sunset"
                + "&timezone=auto&forecast_days=" + days;
        String body = get(url);
        Map<String, Object> root = parseObject(body);
        if (root == null) {
            out.put("error", "api.open-meteo.com did not answer");
            return out;
        }
        Map<String, Object> current = Json.asObject(root.get("current"));
        Map<String, Object> dailyRaw = Json.asObject(root.get("daily"));
        if (current.isEmpty() || dailyRaw.isEmpty()) {
            out.put("error", "Open-Meteo returned no forecast data");
            return out;
        }
        Map<String, Object> locationOut = new LinkedHashMap<>();
        locationOut.put("name", name.isBlank() ? "当前位置" : name);
        locationOut.put("region", region);
        locationOut.put("country", country);
        locationOut.put("latitude", latitude);
        locationOut.put("longitude", longitude);
        {
            String description = weatherText(Json.integer(current, "weather_code", -1));
            Map<String, Object> currentOut = new LinkedHashMap<>();
            currentOut.put("temperature_c", Json.decimal(current, "temperature_2m", 0));
            currentOut.put("feels_like_c",
                    Json.decimal(current, "apparent_temperature", 0));
            currentOut.put("humidity_percent",
                    Json.integer(current, "relative_humidity_2m", 0));
            currentOut.put("precipitation_mm",
                    Json.decimal(current, "precipitation", 0));
            currentOut.put("weather", description);
            currentOut.put("weather_code", Json.integer(current, "weather_code", -1));
            currentOut.put("wind_kmh", Json.decimal(current, "wind_speed_10m", 0));
            currentOut.put("wind_direction",
                    windDirection(Json.decimal(current, "wind_direction_10m", 0)));
            currentOut.put("pressure_hpa", 0);
            currentOut.put("visibility_km", 0);
            currentOut.put("uv_index", 0);
            currentOut.put("observed_at", Json.str(current, "time", ""));

            List<Object> times = Json.asList(dailyRaw.get("time"));
            List<Object> codes = Json.asList(dailyRaw.get("weather_code"));
            List<Object> maxima = Json.asList(dailyRaw.get("temperature_2m_max"));
            List<Object> minima = Json.asList(dailyRaw.get("temperature_2m_min"));
            List<Object> rain = Json.asList(dailyRaw.get("precipitation_probability_max"));
            List<Object> wind = Json.asList(dailyRaw.get("wind_speed_10m_max"));
            List<Object> sunrise = Json.asList(dailyRaw.get("sunrise"));
            List<Object> sunset = Json.asList(dailyRaw.get("sunset"));
            List<Object> daily = new ArrayList<>();
            for (int i = 0; i < times.size() && i < days; ++i) {
                int code = intAt(codes, i);
                daily.add(Json.map(
                        "date", String.valueOf(times.get(i)),
                        "weather", weatherText(code),
                        "weather_code", code,
                        "temp_max_c", doubleAt(maxima, i),
                        "temp_min_c", doubleAt(minima, i),
                        "temp_avg_c", (doubleAt(maxima, i) + doubleAt(minima, i)) / 2.0,
                        "precipitation_chance_percent", intAt(rain, i),
                        "wind_max_kmh", doubleAt(wind, i),
                        "sunrise", stringAt(sunrise, i),
                        "sunset", stringAt(sunset, i)));
            }
            out.put("location", locationOut);
            out.put("current", currentOut);
            out.put("daily", daily);
            out.put("timezone", Json.str(root, "timezone",
                    timezone.isBlank() ? "" : timezone));
            out.put("summary", summary(locationOut, currentOut, daily,
                    "Open-Meteo（免密钥）"));
            out.put("ok", true);
        }
        return out;
    }

    /* ------------------------------------------------------------------ helpers */

    private String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(6000,
                            Math.min(20000, settings.onlineTimeoutMs()))))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
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

    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return Json.asObject(Json.parse(body.substring(start, end + 1)));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Map<String, Object> parseCoordinates(String location) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[,;]\\s*"
                        + "(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")
                .matcher(location == null ? "" : location);
        if (!matcher.matches()) {
            return null;
        }
        return Json.map("latitude", Double.parseDouble(matcher.group(1)),
                "longitude", Double.parseDouble(matcher.group(2)));
    }

    private static Object first(Object value) {
        List<Object> list = Json.asList(value);
        return list.isEmpty() ? null : list.get(0);
    }

    private static String firstValue(Object value) {
        Object item = first(value);
        if (item instanceof Map<?, ?> map) {
            Object text = map.get("value");
            return text instanceof String string ? string : "";
        }
        return item instanceof String string ? string : "";
    }

    private static Map<String, Object> noonHour(Map<String, Object> day) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        List<Object> hours = Json.asList(day.get("hourly"));
        for (Object raw : hours) {
            Map<String, Object> hour = Json.asObject(raw);
            if ("1200".equals(Json.str(hour, "time", ""))) {
                return hour;
            }
            if (fallback.isEmpty() && !hours.isEmpty()) {
                fallback = hour;
            }
        }
        return fallback;
    }

    private static double decimal(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int integer(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int intAt(List<Object> list, int index) {
        if (index >= list.size() || list.get(index) == null) {
            return 0;
        }
        Object value = list.get(index);
        return value instanceof Number number ? number.intValue()
                : value instanceof String text ? integer(Json.map("v", text), "v") : 0;
    }

    private static double doubleAt(List<Object> list, int index) {
        if (index >= list.size() || list.get(index) == null) {
            return 0;
        }
        Object value = list.get(index);
        return value instanceof Number number ? number.doubleValue()
                : value instanceof String text ? decimal(Json.map("v", text), "v") : 0;
    }

    private static String stringAt(List<Object> list, int index) {
        if (index >= list.size()) {
            return "";
        }
        Object value = list.get(index);
        return value instanceof String text ? text : "";
    }

    private static String windDirection(double degrees) {
        String[] names = {"北", "东北偏北", "东北", "东北偏东", "东", "东南偏东", "东南",
                "东南偏南", "南", "西南偏南", "西南", "西南偏西", "西", "西北偏西",
                "西北", "西北偏北"};
        int index = (int) Math.round((degrees % 360) / 22.5) % 16;
        return names[index < 0 ? index + 16 : index] + "风";
    }

    private static String weatherText(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "少云";
            case 2 -> "局部多云";
            case 3 -> "阴";
            case 45 -> "雾";
            case 48 -> "冻雾";
            case 51 -> "毛毛雨";
            case 53 -> "小雨";
            case 55 -> "中雨";
            case 56, 66 -> "冻雨";
            case 57, 67 -> "强冻雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "米雪";
            case 80 -> "阵雨";
            case 81 -> "强阵雨";
            case 82 -> "暴雨";
            case 85 -> "阵雪";
            case 86 -> "强阵雪";
            case 95 -> "雷阵雨";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知";
        };
    }

    private static String translateWeather(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String lower = description.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("thundery") && lower.contains("hail")) return "雷暴伴冰雹";
        if (lower.contains("thundery")) return "雷阵雨";
        if (lower.contains("heavy rain")) return "大雨";
        if (lower.contains("moderate rain")) return "中雨";
        if (lower.contains("light rain")) return "小雨";
        if (lower.contains("patchy rain")) return "零星降雨";
        if (lower.contains("freezing")) return "冻雨";
        if (lower.contains("heavy snow")) return "大雪";
        if (lower.contains("moderate snow")) return "中雪";
        if (lower.contains("light snow")) return "小雪";
        if (lower.contains("blizzard")) return "暴风雪";
        if (lower.contains("sleet")) return "雨夹雪";
        if (lower.contains("drizzle")) return "毛毛雨";
        if (lower.contains("overcast")) return "阴";
        if (lower.contains("partly cloudy")) return "局部多云";
        if (lower.contains("cloudy")) return "多云";
        if (lower.contains("mist")) return "薄雾";
        if (lower.contains("fog")) return "雾";
        if (lower.contains("haze") || lower.contains("smoky")) return "霾";
        if (lower.contains("sunny") || lower.contains("clear")) return "晴";
        return description;
    }

    private static String summary(Map<String, Object> location,
                                  Map<String, Object> current,
                                  List<Object> daily,
                                  String source) {
        StringBuilder text = new StringBuilder();
        String name = Json.str(location, "name", "");
        String region = Json.str(location, "region", "");
        String country = Json.str(location, "country", "");
        text.append(name);
        String area = region.isBlank() ? country
                : (country.isBlank() || region.equals(country) ? region
                : region + " · " + country);
        if (!area.isBlank()) {
            text.append("（").append(area).append("）");
        }
        text.append('\n');
        text.append("当前：").append(Json.str(current, "weather", "未知"))
                .append(" · ").append(number(current, "temperature_c")).append("°C")
                .append("（体感 ").append(number(current, "feels_like_c")).append("°C）")
                .append(" · 湿度 ").append(Json.integer(current, "humidity_percent", 0))
                .append("% · 风 ").append(number(current, "wind_kmh")).append(" km/h");
        if (!Json.str(current, "wind_direction", "").isBlank()) {
            text.append(" ").append(Json.str(current, "wind_direction", ""));
        }
        text.append(" · 降水 ").append(number(current, "precipitation_mm"))
                .append(" mm");
        if (!Json.str(current, "observed_at", "").isBlank()) {
            text.append(" · 观测 ").append(Json.str(current, "observed_at", ""));
        }
        text.append('\n');
        for (Object raw : daily) {
            Map<String, Object> day = Json.asObject(raw);
            text.append(Json.str(day, "date", ""))
                    .append("：").append(Json.str(day, "weather", "未知"))
                    .append(" · ").append(number(day, "temp_min_c")).append(" ~ ")
                    .append(number(day, "temp_max_c")).append("°C")
                    .append(" · 降水概率 ")
                    .append(Json.integer(day, "precipitation_chance_percent", 0))
                    .append("%");
            String sunrise = Json.str(day, "sunrise", "");
            String sunset = Json.str(day, "sunset", "");
            if (!sunrise.isBlank() || !sunset.isBlank()) {
                text.append(" · 日出 ").append(sunrise)
                        .append(" 日落 ").append(sunset);
            }
            text.append('\n');
        }
        text.append("数据来源：").append(source).append("，无需任何 API Key 或注册。");
        return text.toString();
    }

    private static String number(Map<String, Object> map, String key) {
        double value = decimal(map, key);
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
