package codeforge.server;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Universal outbound HTTP client used by the agent and the console API panel.
 * It can call any JSON/REST endpoint; credentials are injected from the local
 * vault by name and are never echoed in the response or logs.
 */
public final class ApiClient {
    private final KeyStore secrets;
    private final HttpClient http;

    public ApiClient(KeyStore secrets) {
        this.secrets = secrets;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> call(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("kind", "api");
        String method = Json.str(request, "method", "GET").trim().toUpperCase(Locale.ROOT);
        if (!List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .contains(method)) {
            out.put("error", "Unsupported HTTP method: " + method);
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }
        String url = Json.str(request, "url", "").trim();
        if (url.isBlank()) {
            out.put("error", "url is required");
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (!(lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://"))) {
            out.put("error", "Only http:// and https:// URLs are supported");
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        Object rawHeaders = request.get("headers");
        if (rawHeaders instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawHeaders).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    headers.put(String.valueOf(entry.getKey()).trim(),
                            String.valueOf(entry.getValue()));
                }
            }
        } else if (rawHeaders instanceof String text && !text.isBlank()) {
            Map<String, Object> parsed = parseObject(text);
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getValue() != null) {
                    headers.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }

        StringBuilder query = new StringBuilder();
        Object rawQuery = request.get("query");
        if (rawQuery instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawQuery).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    query.append(query.length() == 0 ? "" : "&")
                            .append(encode(String.valueOf(entry.getKey()))).append('=')
                            .append(encode(String.valueOf(entry.getValue())));
                }
            }
        }

        String secretName = firstNonBlank(Json.str(request, "secret_name", ""),
                Json.str(request, "secret", ""));
        String storedSecret = secretName.isBlank() ? "" : secrets.secretValue(secretName);
        String auth = Json.str(request, "auth", "bearer").trim().toLowerCase(Locale.ROOT);
        if (!storedSecret.isBlank()) {
            if ("query".equals(auth)) {
                String param = firstNonBlank(Json.str(request, "param", ""), "access_token");
                query.append(query.length() == 0 ? "" : "&")
                        .append(encode(param)).append('=').append(encode(storedSecret));
            } else {
                String header = firstNonBlank(Json.str(request, "header", ""), "Authorization");
                String prefix = Json.str(request, "prefix", "");
                String value;
                if (!prefix.isBlank()) {
                    value = prefix + storedSecret;
                } else if ("token".equals(auth)) {
                    value = "token " + storedSecret;
                } else if ("header".equals(auth)) {
                    value = storedSecret;
                } else {
                    value = "Bearer " + storedSecret;
                }
                headers.put(header, value);
            }
            out.put("secret_used", secretName);
        } else if (!secretName.isBlank()) {
            out.put("secret_missing", secretName);
        }

        if (query.length() > 0) {
            url += (url.contains("?") ? "&" : "?") + query;
        }

        String body = "";
        Object rawBody = request.get("body");
        boolean jsonBody = false;
        if (rawBody instanceof Map<?, ?> || rawBody instanceof List<?>) {
            body = Json.write(rawBody);
            jsonBody = true;
        } else if (rawBody != null) {
            body = String.valueOf(rawBody);
        }
        if (!body.isBlank() && !containsHeader(headers, "content-type")) {
            headers.put("Content-Type", jsonBody ? "application/json" : "text/plain; charset=utf-8");
        }
        if (!containsHeader(headers, "accept")) {
            headers.put("Accept", "application/json, text/plain, */*");
        }
        if (!containsHeader(headers, "user-agent")) {
            headers.put("User-Agent", "CodeForge-AI/1.2.2");
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, Math.min(120,
                            Json.integer(request, "timeout", 30)))));
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            HttpRequest.BodyPublisher publisher = body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            builder.method(method, publisher);

            HttpResponse<byte[]> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int maxChars = Math.max(1000, Math.min(400000,
                    Json.integer(request, "max_chars", 60000)));
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            String contentType = response.headers().firstValue("content-type").orElse("");
            String responseBody = new String(bytes, StandardCharsets.UTF_8);
            boolean truncated = responseBody.length() > maxChars;
            if (truncated) {
                responseBody = responseBody.substring(0, maxChars) + "\n…[truncated]";
            }

            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.List<String>> entry
                    : response.headers().map().entrySet()) {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if ("authorization".equals(key) || "set-cookie".equals(key)
                        || "cookie".equals(key)) {
                    continue;
                }
                responseHeaders.put(key, String.join(", ", entry.getValue()));
            }

            out.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
            out.put("status", response.statusCode());
            out.put("method", method);
            out.put("url", url);
            out.put("content_type", contentType);
            out.put("headers", responseHeaders);
            out.put("body", responseBody);
            out.put("truncated", truncated);
            out.put("bytes", bytes.length);
            if (contentType.toLowerCase(Locale.ROOT).contains("json")
                    || responseBody.stripLeading().startsWith("{")
                    || responseBody.stripLeading().startsWith("[")) {
                try {
                    out.put("json", Json.parse(responseBody));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ex) {
            out.put("error", ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    private static boolean containsHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> parseObject(String text) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return Json.asObject(Json.parse(text.substring(start, end + 1)));
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim()
                : (second == null ? "" : second.trim());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}