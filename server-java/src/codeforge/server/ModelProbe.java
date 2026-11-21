package codeforge.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies that the active model can follow CodeForge's tool protocol. The
 * probe asks for one small build and validates the <codeforge_build> JSON and
 * the generated source; a model that only chats is reported as weak.
 */
final class ModelProbe {
    private static final String SYSTEM_PROMPT =
            "You are CodeForge AI's local coding agent.\n"
            + "When asked to build something, reply with exactly one tool call and nothing else:\n"
            + "<codeforge_build>{\"language\":\"python\",\"name\":\"capability_probe\","
            + "\"gui\":false,\"destination\":\"desktop\",\"code\":\"<complete runnable Python source>\"}"
            + "</codeforge_build>\n"
            + "Rules: the code field must contain real, complete source with no placeholders "
            + "and no markdown fences; the JSON must be valid and the tag must be closed. "
            + "Do not answer with prose.";
    private static final String USER_PROMPT =
            "Build a Python script named capability_probe that prints exactly PROBE_OK. "
            + "Emit the tool call now.";
    private static final String[] PLACEHOLDERS = {
            "complete source for", "your code here", "placeholder", "lorem ipsum",
            "todo", "insert code", "rest of the code"
    };

    private ModelProbe() {
    }

    static Map<String, Object> run(String baseUrl, String modelName, String modelId,
                                   ModelManager models) {
        long started = System.currentTimeMillis();
        Map<String, Object> result;
        try {
            result = request(baseUrl, modelName, modelId);
        } catch (Exception ex) {
            result = failure("error", "Probe request failed: " + ex.getMessage(), "");
        }
        result.put("latency_ms", System.currentTimeMillis() - started);
        result.put("source", "probe");
        models.recordCapability(modelId, result);
        return result;
    }

    private static Map<String, Object> request(String baseUrl, String modelName,
                                               String modelId) throws Exception {
        String endpoint = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        Map<String, Object> payload = Json.map(
                "model", modelName == null || modelName.isBlank() ? modelId : modelName,
                "messages", List.of(
                        Json.map("role", "system", "content", SYSTEM_PROMPT),
                        Json.map("role", "user", "content", USER_PROMPT)),
                "temperature", 0.1,
                "top_p", 0.9,
                "max_tokens", 2048,
                "stream", false);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = null;
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofMinutes(8))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                Json.write(payload), StandardCharsets.UTF_8))
                        .build();
                response = http.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    break;
                }
                last = new IllegalStateException("HTTP " + status + ": "
                        + excerpt(response.body(), 160));
                if (status != 502 && status != 503) {
                    break;
                }
            } catch (Exception ex) {
                last = ex;
            }
            if (attempt < 3) {
                Thread.sleep(4000L);
            }
        }
        if (response == null || response.statusCode() != 200) {
            return failure("error", "Inference endpoint unavailable: "
                    + (last == null ? "no response" : last.getMessage()), "");
        }
        Map<String, Object> document = Json.asObject(Json.parse(response.body()));
        List<Object> choices = Json.asList(document.get("choices"));
        if (choices.isEmpty()) {
            return failure("weak", "Model returned no choices", response.body());
        }
        Map<String, Object> message = Json.asObject(
                Json.asObject(choices.get(0)).get("message"));
        String content = Json.str(message, "content", "");
        return validate(content);
    }

    static Map<String, Object> validate(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            return failure("weak", "Model returned an empty reply", text);
        }
        String json = extractJson(text);
        if (json.isEmpty()) {
            return failure("weak", "No complete <codeforge_build> JSON block found", text);
        }
        Map<String, Object> tool;
        try {
            tool = Json.asObject(Json.parse(json));
        } catch (Exception ex) {
            return failure("weak", "Tool JSON is not valid JSON: " + ex.getMessage(), text);
        }
        if (tool.isEmpty()) {
            return failure("weak", "Tool JSON is empty", text);
        }
        String language = Json.str(tool, "language", "").toLowerCase(Locale.ROOT);
        if (!language.equals("python") && !language.equals("py") && !language.equals("python3")) {
            return failure("weak", "Tool call used language '" + language
                    + "' instead of python", text);
        }
        String code = Json.str(tool, "code", "");
        if (code.isBlank()) {
            code = Json.str(tool, "source", "");
        }
        if (code.isBlank()) {
            return failure("weak", "Tool call has no code or source", text);
        }
        String lowered = code.toLowerCase(Locale.ROOT);
        for (String placeholder : PLACEHOLDERS) {
            if (lowered.contains(placeholder)) {
                return failure("weak", "Generated code contains a placeholder: "
                        + placeholder, text);
            }
        }
        if (!lowered.contains("print")) {
            return failure("weak", "Generated code has no print statement", text);
        }
        if (!code.contains("PROBE_OK")) {
            return failure("weak", "Generated code does not print PROBE_OK", text);
        }
        String name = Json.str(tool, "name", "").strip();
        if (name.isEmpty()) {
            return failure("weak", "Tool call has no name", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "pass");
        result.put("score", 100);
        result.put("reason", "Valid <codeforge_build> JSON with runnable Python source");
        result.put("language", language);
        result.put("name", name);
        result.put("code_chars", code.length());
        result.put("reply_chars", text.length());
        return result;
    }

    private static String extractJson(String text) {
        int marker = text.indexOf("<codeforge_build>");
        int from;
        if (marker >= 0) {
            from = marker + "<codeforge_build>".length();
        } else {
            int plain = text.indexOf("codeforge_build");
            if (plain < 0) {
                return "";
            }
            from = plain + "codeforge_build".length();
        }
        int start = text.indexOf('{', from);
        if (start < 0) {
            return "";
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                ++depth;
            } else if (c == '}') {
                --depth;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private static Map<String, Object> failure(String status, String reason, String reply) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("score", 0);
        result.put("reason", reason);
        result.put("reply_chars", reply == null ? 0 : reply.length());
        result.put("excerpt", excerpt(reply, 240));
        return result;
    }

    private static String excerpt(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
