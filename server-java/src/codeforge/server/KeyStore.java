package codeforge.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Local API key vault. Keys live only in config.json on this machine; the
 * active entry is mirrored into llm.online so the C++ core keeps working
 * unchanged. The API never returns a stored key, only a masked hint.
 */
public final class KeyStore {
    private static final String LEGACY_ID = "default";

    private final Path configFile;
    private final HttpClient http;

    public KeyStore(Path root) {
        this.configFile = root.resolve("config.json");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public synchronized Map<String, Object> snapshot() {
        try {
            Map<String, Object> doc = readDoc();
            List<Map<String, Object>> providers = rawProviders(doc);
            String activeId = activeId(doc, providers);
            return snapshotOf(doc, providers, activeId);
        } catch (Exception ex) {
            return failure("Cannot read config.json: " + ex.getMessage());
        }
    }

    public synchronized Map<String, Object> setOnlineEnabled(boolean enabled) {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> llm = child(doc, "llm");
            Map<String, Object> online = child(llm, "online");
            online.put("enabled", enabled);
            writeDoc(doc);
            List<Map<String, Object>> providers = rawProviders(doc);
            return snapshotOf(doc, providers, activeId(doc, providers));
        } catch (Exception ex) {
            return failure("Cannot save config.json: " + ex.getMessage());
        }
    }

    /* ---------------------------------------------------------------- */
    /* generic secrets: API keys, GitHub/GitCode tokens, service tokens  */
    /* ---------------------------------------------------------------- */

    public synchronized Map<String, Object> secretsView() {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> secrets = Json.asObject(doc.get("secrets"));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<String, Object> entry : secrets.entrySet()) {
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                items.add(Json.map(
                        "name", entry.getKey(),
                        "hint", maskSecret(value),
                        "length", value.length()));
            }
            items.sort((a, b) -> Json.str(a, "name", "")
                    .compareToIgnoreCase(Json.str(b, "name", "")));
            return Json.map("ok", true, "secrets", items);
        } catch (Exception ex) {
            return failure("Cannot read secrets: " + ex.getMessage());
        }
    }

    public synchronized Map<String, Object> setSecret(String rawName, String value) {
        String name = normalizeSecretName(rawName);
        if (name.isBlank()) {
            return failure("Secret name must contain letters, digits, '_' or '-'.");
        }
        if (value == null || value.isBlank()) {
            return failure("Secret value is empty.");
        }
        if (value.length() > 20000) {
            return failure("Secret value is too long.");
        }
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> secrets = child(doc, "secrets");
            secrets.put(name, value.trim());
            writeDoc(doc);
            Map<String, Object> out = new LinkedHashMap<>(secretsView());
            out.put("message", "Saved " + name);
            return out;
        } catch (Exception ex) {
            return failure("Cannot save secret: " + ex.getMessage());
        }
    }

    public synchronized Map<String, Object> deleteSecret(String rawName) {
        String name = normalizeSecretName(rawName);
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> secrets = Json.asObject(doc.get("secrets"));
            String actual = findSecretKey(secrets, name);
            if (actual == null) {
                return failure("Secret not found: " + name);
            }
            secrets.remove(actual);
            writeDoc(doc);
            Map<String, Object> out = new LinkedHashMap<>(secretsView());
            out.put("message", "Deleted " + actual);
            return out;
        } catch (Exception ex) {
            return failure("Cannot delete secret: " + ex.getMessage());
        }
    }

    /** Internal use only. Never send this value through an API response. */
    public synchronized String secretValue(String rawName) {
        String name = normalizeSecretName(rawName);
        if (name.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> secrets = Json.asObject(doc.get("secrets"));
            String actual = findSecretKey(secrets, name);
            return actual == null ? "" : Json.str(secrets, actual, "");
        } catch (Exception ex) {
            return "";
        }
    }

    public synchronized boolean hasSecret(String rawName) {
        return !secretValue(rawName).isBlank();
    }

    private static String normalizeSecretName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        return name.replaceAll("[^A-Za-z0-9_.-]", "");
    }

    private static String findSecretKey(Map<String, Object> secrets, String name) {
        for (String key : secrets.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 8) {
            return "••••";
        }
        return "••••" + value.substring(value.length() - 4);
    }

    public synchronized Map<String, Object> agentSettings() {
        try {
            return agentView(readDoc(), configFile.toString());
        } catch (Exception ex) {
            return failure("Cannot read config.json: " + ex.getMessage());
        }
    }

    public synchronized Map<String, Object> setAgentSettings(Map<String, Object> request) {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> agent = child(doc, "agent");
            if (request.containsKey("enabled")) {
                Object enabled = request.get("enabled");
                if (!(enabled instanceof Boolean flag)) {
                    return failure("enabled must be true or false");
                }
                agent.put("enabled", flag);
            }
            if (request.containsKey("max_steps")) {
                int value = Json.integer(request, "max_steps", 128);
                agent.put("max_steps", Math.max(1, Math.min(512, value)));
            }
            if (request.containsKey("command_timeout_sec")) {
                int value = Json.integer(request, "command_timeout_sec", 3600);
                agent.put("command_timeout_sec", Math.max(1, Math.min(86400, value)));
            }
            if (request.containsKey("max_feedback_chars")) {
                int value = Json.integer(request, "max_feedback_chars", 48000);
                agent.put("max_feedback_chars", Math.max(4000, Math.min(200000, value)));
            }
            if (request.containsKey("auto_install_tools")) {
                Object install = request.get("auto_install_tools");
                if (!(install instanceof Boolean flag)) {
                    return failure("auto_install_tools must be true or false");
                }
                agent.put("auto_install_tools", flag);
            }
            if (request.containsKey("install_timeout_sec")) {
                int value = Json.integer(request, "install_timeout_sec", 1800);
                agent.put("install_timeout_sec", Math.max(60, Math.min(86400, value)));
            }
            if (request.containsKey("auto_learn_software")) {
                Object learn = request.get("auto_learn_software");
                if (!(learn instanceof Boolean flag)) {
                    return failure("auto_learn_software must be true or false");
                }
                agent.put("auto_learn_software", flag);
            }
            if (request.containsKey("auto_compile")) {
                Object compile = request.get("auto_compile");
                if (!(compile instanceof Boolean flag)) {
                    return failure("auto_compile must be true or false");
                }
                agent.put("auto_compile", flag);
            }
            if (request.containsKey("allow_publish")) {
                Object publish = request.get("allow_publish");
                if (!(publish instanceof Boolean flag)) {
                    return failure("allow_publish must be true or false");
                }
                agent.put("allow_publish", flag);
            }
            writeDoc(doc);
            return agentView(doc, configFile.toString());
        } catch (Exception ex) {
            return failure("Cannot save config.json: " + ex.getMessage());
        }
    }

    private static Map<String, Object> agentView(Map<String, Object> doc, String configPath) {
        Map<String, Object> agent = Json.asObject(doc.get("agent"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("config", configPath);
        out.put("enabled", Json.bool(agent, "enabled", true));
        out.put("max_steps", clamp(Json.integer(agent, "max_steps", 128), 1, 512));
        out.put("command_timeout_sec",
                clamp(Json.integer(agent, "command_timeout_sec", 3600), 1, 86400));
        out.put("max_feedback_chars",
                clamp(Json.integer(agent, "max_feedback_chars", 48000), 4000, 200000));
        out.put("auto_install_tools", Json.bool(agent, "auto_install_tools", true));
        out.put("auto_compile", Json.bool(agent, "auto_compile", true));
        out.put("install_timeout_sec",
                clamp(Json.integer(agent, "install_timeout_sec", 1800), 60, 86400));
        out.put("auto_learn_software", Json.bool(agent, "auto_learn_software", true));
        out.put("allow_publish", Json.bool(agent, "allow_publish", false));
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public synchronized Map<String, Object> setMaxTokens(int requested) {
        int value = Math.max(AppSettings.MIN_MAX_TOKENS,
                Math.min(AppSettings.MAX_MAX_TOKENS, requested));
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> llm = child(doc, "llm");
            llm.put("max_tokens", value);
            writeDoc(doc);
            return Json.map(
                    "ok", true,
                    "max_tokens", value,
                    "min", AppSettings.MIN_MAX_TOKENS,
                    "max", AppSettings.MAX_MAX_TOKENS,
                    "message", "Reply limit saved: " + value
                            + " tokens; online providers apply their own output cap.");
        } catch (Exception ex) {
            return failure("Cannot save config.json: " + ex.getMessage());
        }
    }

    public synchronized String activeLabel() {
        try {
            Map<String, Object> doc = readDoc();
            List<Map<String, Object>> providers = rawProviders(doc);
            String activeId = activeId(doc, providers);
            for (Map<String, Object> provider : providers) {
                if (activeId.equals(Json.str(provider, "id", ""))) {
                    String name = Json.str(provider, "name", "").trim();
                    return name.isEmpty() ? Json.str(provider, "model", "") : name;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public synchronized Map<String, Object> upsert(Map<String, Object> request) {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> llm = child(doc, "llm");
            List<Map<String, Object>> providers = materialize(doc);
            String id = Json.str(request, "id", "").trim();
            String name = Json.str(request, "name", "").trim();
            String baseUrl = normalizeBase(Json.str(request, "base_url", ""));
            String model = Json.str(request, "model", "").trim();
            String key = cleanKey(Json.str(request, "api_key", ""));
            boolean activate = Json.bool(request, "activate", false);

            if (baseUrl.isEmpty()) {
                return failure("Base URL is required");
            }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                return failure("Base URL must start with http:// or https://");
            }
            try {
                URI.create(baseUrl);
            } catch (Exception ex) {
                return failure("Base URL is not a valid URL");
            }
            if (model.isEmpty()) {
                return failure("Model name is required");
            }

            Map<String, Object> target = null;
            if (!id.isEmpty()) {
                for (Map<String, Object> provider : providers) {
                    if (id.equals(Json.str(provider, "id", ""))) {
                        target = provider;
                        break;
                    }
                }
                if (target == null) {
                    return failure("Key entry not found");
                }
            }
            if (target == null) {
                if (key.isEmpty()) {
                    return failure("API key is required");
                }
                id = uniqueId(providers, name, baseUrl);
                target = new LinkedHashMap<>();
                target.put("id", id);
                target.put("name", name.isEmpty() ? guessName(baseUrl) : name);
                target.put("base_url", baseUrl);
                target.put("model", model);
                target.put("api_key", key);
                target.put("created_at", Instant.now().toString());
                target.put("updated_at", Instant.now().toString());
                providers.add(target);
                if (providers.size() == 1) {
                    activate = true;
                }
            } else {
                if (!key.isEmpty()) {
                    target.put("api_key", key);
                }
                if (Json.str(target, "api_key", "").isBlank()) {
                    return failure("API key is required");
                }
                if (!name.isEmpty()) {
                    target.put("name", name);
                }
                target.put("base_url", baseUrl);
                target.put("model", model);
                target.put("updated_at", Instant.now().toString());
            }

            String activeId = activeId(doc, providers);
            if (activate || activeId.isEmpty()) {
                activeId = id;
            }
            llm.put("active_key", activeId);
            llm.put("keys", providers);
            syncActive(llm, providers, activeId);
            writeDoc(doc);
            return snapshotOf(doc, providers, activeId);
        } catch (IllegalArgumentException ex) {
            return failure(ex.getMessage());
        } catch (Exception ex) {
            return failure("Cannot save config.json: " + ex.getMessage());
        }
    }

    public synchronized Map<String, Object> activate(String id) {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> llm = child(doc, "llm");
            List<Map<String, Object>> providers = materialize(doc);
            boolean found = false;
            for (Map<String, Object> provider : providers) {
                if (id.equals(Json.str(provider, "id", ""))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return failure("Key entry not found");
            }
            llm.put("active_key", id);
            llm.put("keys", providers);
            syncActive(llm, providers, id);
            writeDoc(doc);
            return snapshotOf(doc, providers, id);
        } catch (IllegalArgumentException ex) {
            return failure(ex.getMessage());
        } catch (Exception ex) {
            return failure("Cannot save config.json: " + ex.getMessage());
        }
    }

    public synchronized Map<String, Object> remove(String id) {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> llm = child(doc, "llm");
            List<Map<String, Object>> providers = materialize(doc);
            String previousActive = activeId(doc, providers);
            Map<String, Object> target = null;
            for (Map<String, Object> provider : providers) {
                if (id.equals(Json.str(provider, "id", ""))) {
                    target = provider;
                    break;
                }
            }
            if (target == null) {
                return failure("Key entry not found");
            }
            providers.remove(target);
            String activeId = previousActive;
            if (id.equals(previousActive)) {
                activeId = providers.isEmpty()
                        ? "" : Json.str(providers.get(0), "id", "");
            }
            if (activeId.isEmpty()) {
                llm.remove("active_key");
                Map<String, Object> online = child(llm, "online");
                online.put("api_key", "");
            } else {
                llm.put("active_key", activeId);
            }
            llm.put("keys", providers);
            if (!activeId.isEmpty()) {
                syncActive(llm, providers, activeId);
            }
            writeDoc(doc);
            return snapshotOf(doc, providers, activeId);
        } catch (IllegalArgumentException ex) {
            return failure(ex.getMessage());
        } catch (Exception ex) {
            return failure("Cannot save config.json: " + ex.getMessage());
        }
    }

    public Map<String, Object> test(Map<String, Object> request) {
        String id = Json.str(request, "id", "").trim();
        String baseUrl = normalizeBase(Json.str(request, "base_url", ""));
        String model = Json.str(request, "model", "").trim();
        String key = cleanKey(Json.str(request, "api_key", ""));
        if (!id.isEmpty()) {
            try {
                List<Map<String, Object>> providers = rawProviders(readDoc());
                Map<String, Object> found = null;
                for (Map<String, Object> provider : providers) {
                    if (id.equals(Json.str(provider, "id", ""))) {
                        found = provider;
                        break;
                    }
                }
                if (found == null) {
                    return failure("Key entry not found");
                }
                baseUrl = normalizeBase(Json.str(found, "base_url", ""));
                model = Json.str(found, "model", "");
                key = cleanKey(Json.str(found, "api_key", ""));
            } catch (Exception ex) {
                return failure("Cannot read config.json: " + ex.getMessage());
            }
        }
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return failure("Base URL and model are required");
        }
        if (key.isEmpty()) {
            return failure("No API key to test");
        }
        String endpoint = chatEndpoint(baseUrl);
        Map<String, Object> payload = Json.map(
                "model", model,
                "max_tokens", 8,
                "stream", false,
                "messages", Json.list(Json.map(
                        "role", "user",
                        "content", "Reply with the single word OK.")));
        long started = System.currentTimeMillis();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .header("User-Agent", "CodeForgeAI/1.2.2")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Json.write(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.currentTimeMillis() - started;
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String reply = "";
                String replyModel = model;
                try {
                    Map<String, Object> doc = Json.asObject(Json.parse(body));
                    List<Object> choices = Json.asList(doc.get("choices"));
                    if (!choices.isEmpty()) {
                        Map<String, Object> choice = Json.asObject(choices.get(0));
                        Map<String, Object> message = Json.asObject(choice.get("message"));
                        reply = Json.str(message, "content", "").trim();
                    }
                    String echoed = Json.str(doc, "model", "");
                    if (!echoed.isBlank()) {
                        replyModel = echoed;
                    }
                } catch (Exception ignored) {
                    reply = truncate(body, 160);
                }
                return Json.map(
                        "ok", true,
                        "status", response.statusCode(),
                        "latency_ms", latency,
                        "model", replyModel,
                        "reply", truncate(reply, 160),
                        "endpoint", endpoint);
            }
            String detail = errorDetail(body);
            return Json.map(
                    "ok", false,
                    "status", response.statusCode(),
                    "latency_ms", latency,
                    "endpoint", endpoint,
                    "error", "HTTP " + response.statusCode()
                            + (detail.isEmpty() ? "" : ": " + detail));
        } catch (Exception ex) {
            return Json.map(
                    "ok", false,
                    "endpoint", endpoint,
                    "latency_ms", System.currentTimeMillis() - started,
                    "error", "Connection failed: " + ex.getMessage());
        }
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> doc = Json.asObject(Json.parse(body));
            Object error = doc.get("error");
            if (error instanceof Map<?, ?>) {
                String message = Json.str(Json.asObject(error), "message", "");
                if (!message.isBlank()) {
                    return truncate(message, 240);
                }
            }
            if (error instanceof String text && !text.isBlank()) {
                return truncate(text, 240);
            }
        } catch (Exception ignored) {
        }
        return truncate(body, 240);
    }

    private Map<String, Object> snapshotOf(Map<String, Object> doc,
                                           List<Map<String, Object>> providers,
                                           String activeId) {
        List<Object> view = new ArrayList<>();
        for (Map<String, Object> provider : providers) {
            String id = Json.str(provider, "id", "");
            String key = Json.str(provider, "api_key", "");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", Json.str(provider, "name", ""));
            item.put("base_url", Json.str(provider, "base_url", ""));
            item.put("model", Json.str(provider, "model", ""));
            item.put("key_hint", maskKey(key));
            item.put("has_key", !key.isBlank());
            item.put("active", id.equals(activeId));
            item.put("created_at", Json.str(provider, "created_at", ""));
            item.put("updated_at", Json.str(provider, "updated_at", ""));
            view.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("config", configFile.toString());
        out.put("active_id", activeId);
        out.put("online_enabled", onlineEnabled(doc));
        out.put("providers", view);
        return out;
    }

    private List<Map<String, Object>> rawProviders(Map<String, Object> doc) {
        Map<String, Object> llm = Json.asObject(doc.get("llm"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : Json.asList(llm.get("keys"))) {
            Map<String, Object> provider = new LinkedHashMap<>(Json.asObject(item));
            if (Json.str(provider, "id", "").isBlank()) {
                continue;
            }
            out.add(provider);
        }
        if (out.isEmpty()) {
            Map<String, Object> online = Json.asObject(llm.get("online"));
            String key = cleanKey(Json.str(online, "api_key", ""));
            if (!key.isEmpty()) {
                Map<String, Object> legacy = new LinkedHashMap<>();
                String baseUrl = normalizeBase(Json.str(online, "base_url", ""));
                legacy.put("id", LEGACY_ID);
                legacy.put("name", guessName(baseUrl));
                legacy.put("base_url", baseUrl.isEmpty()
                        ? "https://api.deepseek.com" : baseUrl);
                legacy.put("model", Json.str(online, "model", "deepseek-chat"));
                legacy.put("api_key", key);
                out.add(legacy);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> materialize(Map<String, Object> doc) {
        Map<String, Object> llm = child(doc, "llm");
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Object item : Json.asList(llm.get("keys"))) {
            Map<String, Object> provider = new LinkedHashMap<>(Json.asObject(item));
            if (!Json.str(provider, "id", "").isBlank()) {
                providers.add(provider);
            }
        }
        if (providers.isEmpty()) {
            Map<String, Object> online = Json.asObject(llm.get("online"));
            String key = cleanKey(Json.str(online, "api_key", ""));
            if (!key.isEmpty()) {
                Map<String, Object> imported = new LinkedHashMap<>();
                String baseUrl = normalizeBase(Json.str(online, "base_url", ""));
                imported.put("id", LEGACY_ID);
                imported.put("name", guessName(baseUrl));
                imported.put("base_url", baseUrl.isEmpty()
                        ? "https://api.deepseek.com" : baseUrl);
                imported.put("model", Json.str(online, "model", "deepseek-chat"));
                imported.put("api_key", key);
                imported.put("created_at", Instant.now().toString());
                imported.put("updated_at", Instant.now().toString());
                providers.add(imported);
            }
        }
        return providers;
    }

    private static String activeId(Map<String, Object> doc,
                                   List<Map<String, Object>> providers) {
        Map<String, Object> llm = Json.asObject(doc.get("llm"));
        String stored = Json.str(llm, "active_key", "").trim();
        for (Map<String, Object> provider : providers) {
            if (stored.equals(Json.str(provider, "id", ""))) {
                return stored;
            }
        }
        Map<String, Object> online = Json.asObject(llm.get("online"));
        String onlineKey = cleanKey(Json.str(online, "api_key", ""));
        String onlineUrl = normalizeBase(Json.str(online, "base_url", ""));
        String onlineModel = Json.str(online, "model", "");
        for (Map<String, Object> provider : providers) {
            if (!onlineKey.isEmpty()
                    && onlineKey.equals(cleanKey(Json.str(provider, "api_key", "")))
                    && onlineUrl.equalsIgnoreCase(
                            normalizeBase(Json.str(provider, "base_url", "")))
                    && onlineModel.equals(Json.str(provider, "model", ""))) {
                return Json.str(provider, "id", "");
            }
        }
        return "";
    }

    private static void syncActive(Map<String, Object> llm,
                                   List<Map<String, Object>> providers,
                                   String activeId) {
        for (Map<String, Object> provider : providers) {
            if (!activeId.equals(Json.str(provider, "id", ""))) {
                continue;
            }
            Map<String, Object> online = child(llm, "online");
            online.put("enabled", Json.bool(online, "enabled", true));
            online.put("base_url", Json.str(provider, "base_url", ""));
            online.put("model", Json.str(provider, "model", ""));
            online.put("api_key", Json.str(provider, "api_key", ""));
            return;
        }
    }

    private static boolean onlineEnabled(Map<String, Object> doc) {
        Map<String, Object> llm = Json.asObject(doc.get("llm"));
        Map<String, Object> online = Json.asObject(llm.get("online"));
        return Json.bool(online, "enabled", true);
    }

    private Map<String, Object> readDoc() throws Exception {
        if (!Files.isRegularFile(configFile)) {
            return new LinkedHashMap<>();
        }
        String text = AppSettings.readConfigText(configFile);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "config.json is not valid JSON — fix it before using the key vault");
        }
        Map<String, Object> doc = Json.asObject(parsed);
        if (doc.isEmpty() && !text.trim().equals("{}")) {
            throw new IllegalArgumentException("config.json must contain a JSON object");
        }
        return doc;
    }

    private void writeDoc(Map<String, Object> doc) throws Exception {
        Path temp = configFile.resolveSibling("config.json.tmp");
        Files.writeString(temp, Json.writePretty(doc) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object raw = parent.get(key);
        if (raw instanceof Map<?, ?>) {
            return Json.asObject(raw);
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private static String uniqueId(List<Map<String, Object>> providers,
                                   String name, String baseUrl) {
        String base = slug(name.isEmpty() ? hostOf(baseUrl) : name);
        if (base.isEmpty()) {
            base = "key";
        }
        for (int attempt = 0; attempt < 20; ++attempt) {
            String id = base + "-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 6);
            boolean taken = false;
            for (Map<String, Object> provider : providers) {
                if (id.equals(Json.str(provider, "id", ""))) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return id;
            }
        }
        return "key-" + System.currentTimeMillis();
    }

    private static String slug(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lower.length() && out.length() < 32; ++i) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (c == ' ' || c == '-' || c == '_' || c == '.') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                    out.append('-');
                }
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "" : host;
        } catch (Exception ex) {
            return "";
        }
    }

    static String guessName(String baseUrl) {
        String host = hostOf(baseUrl).toLowerCase(Locale.ROOT);
        if (host.contains("deepseek")) return "DeepSeek";
        if (host.contains("openai")) return "OpenAI";
        if (host.contains("moonshot")) return "Moonshot Kimi";
        if (host.contains("bigmodel") || host.contains("zhipu")) return "Zhipu GLM";
        if (host.contains("dashscope") || host.contains("aliyun")) return "Qwen";
        if (host.contains("siliconflow")) return "SiliconFlow";
        if (host.contains("openrouter")) return "OpenRouter";
        return host.isEmpty() ? "Online brain" : host;
    }

    static String normalizeBase(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    static String cleanKey(String value) {
        String out = value == null ? "" : value.trim();
        if (out.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            out = out.substring(7).trim();
        }
        return out;
    }

    static String chatEndpoint(String baseUrl) {
        String base = normalizeBase(baseUrl);
        if (base.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static String maskKey(String key) {
        String value = cleanKey(key);
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "••••" + value.substring(value.length() - Math.min(2, value.length()));
        }
        return "••••••••" + value.substring(value.length() - 4);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.strip();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static Map<String, Object> failure(String message) {
        return Json.map("ok", false, "error", message);
    }
}
