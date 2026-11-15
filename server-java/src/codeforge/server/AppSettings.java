package codeforge.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Reads memory/online/compliance settings from config.json and the environment.
 * Reloads automatically when config.json changes on disk.
 */
public final class AppSettings {
    private final Path root;
    private final Path configFile;
    private long lastModified = -1;

    private boolean memoryEnabled = true;
    private boolean autoCapture = true;
    private int maxEntries = 4000;
    private int retrievalLimit = 5;

    private boolean onlineEnabled = true;
    private String researchMode = "auto";
    private String searchEndpoint = "";
    private int onlineTimeoutMs = 12000;
    private int maxResults = 3;

    private boolean complianceEnabled = true;
    private String jurisdiction = "auto";
    private boolean onlineRefresh = true;
    private int refreshHours = 24;
    private boolean autoToolchain = true;

    private boolean onlineLlmEnabled = true;
    private String onlineLlmUrl = "https://api.deepseek.com";
    private String onlineLlmModel = "deepseek-chat";
    private String onlineLlmKey = "";
    private int contextSize = 16384;
    private int parallelSlots = 1;

    public AppSettings(Path root) {
        this.root = root;
        this.configFile = root.resolve("config.json");
    }

    public Path root() {
        return root;
    }

    private synchronized void ensure() {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(configFile).toMillis();
        } catch (Exception ex) {
            mtime = 0;
        }
        if (mtime == lastModified) {
            return;
        }
        lastModified = mtime;
        try {
            String text = Files.readString(configFile, StandardCharsets.UTF_8);
            Map<String, Object> doc = Json.asObject(Json.parse(text));
            Map<String, Object> mem = Json.asObject(doc.get("memory"));
            if (!mem.isEmpty()) {
                memoryEnabled = Json.bool(mem, "enabled", memoryEnabled);
                autoCapture = Json.bool(mem, "auto_capture", autoCapture);
                maxEntries = Json.integer(mem, "max_entries", maxEntries);
                retrievalLimit = Json.integer(mem, "retrieval_limit", retrievalLimit);
            }
            Map<String, Object> online = Json.asObject(doc.get("online"));
            if (!online.isEmpty()) {
                onlineEnabled = Json.bool(online, "enabled", onlineEnabled);
                researchMode = Json.str(online, "research", researchMode);
                searchEndpoint = Json.str(online, "search_endpoint", searchEndpoint);
                onlineTimeoutMs = Json.integer(online, "timeout_ms", onlineTimeoutMs);
                maxResults = Json.integer(online, "max_results", maxResults);
            }
            Map<String, Object> llm = Json.asObject(doc.get("llm"));
            contextSize = Json.integer(llm, "context_size", contextSize);
            parallelSlots = Json.integer(llm, "parallel", parallelSlots);
            Map<String, Object> onlineLlm = Json.asObject(llm.get("online"));
            if (!onlineLlm.isEmpty()) {
                onlineLlmEnabled = Json.bool(onlineLlm, "enabled", onlineLlmEnabled);
                onlineLlmUrl = Json.str(onlineLlm, "base_url", onlineLlmUrl);
                onlineLlmModel = Json.str(onlineLlm, "model", onlineLlmModel);
                onlineLlmKey = Json.str(onlineLlm, "api_key", onlineLlmKey);
            }
            Map<String, Object> compliance = Json.asObject(doc.get("compliance"));
            if (!compliance.isEmpty()) {
                complianceEnabled = Json.bool(compliance, "enabled", complianceEnabled);
                jurisdiction = Json.str(compliance, "jurisdiction", jurisdiction);
                onlineRefresh = Json.bool(compliance, "online_refresh", onlineRefresh);
                refreshHours = Json.integer(compliance, "refresh_hours", refreshHours);
            }
            Map<String, Object> toolchains = Json.asObject(doc.get("toolchains"));
            if (!toolchains.isEmpty()) {
                autoToolchain = Json.bool(toolchains, "auto_install", autoToolchain);
            }
        } catch (Exception ignored) {
            // Keep defaults when config.json is absent or invalid.
        }
        applyEnv();
        clamp();
    }

    private void applyEnv() {
        String value = System.getenv("CODEFORGE_MEMORY");
        if (value != null && !value.isBlank()) {
            memoryEnabled = truthy(value);
        }
        value = System.getenv("CODEFORGE_ONLINE");
        if (value != null && !value.isBlank()) {
            onlineEnabled = truthy(value);
        }
        value = System.getenv("CODEFORGE_RESEARCH");
        if (value != null && !value.isBlank()) {
            researchMode = value;
        }
        value = System.getenv("CODEFORGE_JURISDICTION");
        if (value != null && !value.isBlank()) {
            jurisdiction = value;
        }
        value = System.getenv("CODEFORGE_ONLINE_LLM");
        if (value != null && !value.isBlank()) {
            onlineLlmEnabled = truthy(value);
        }
        value = System.getenv("CODEFORGE_ONLINE_LLM_URL");
        if (value != null && !value.isBlank()) {
            onlineLlmUrl = value;
        }
        value = System.getenv("CODEFORGE_ONLINE_LLM_MODEL");
        if (value != null && !value.isBlank()) {
            onlineLlmModel = value;
        }
        value = System.getenv("CODEFORGE_ONLINE_LLM_KEY");
        if (value != null && !value.isBlank()) {
            onlineLlmKey = value;
        }
        value = System.getenv("CODEFORGE_AUTO_TOOLCHAIN");
        if (value != null && !value.isBlank()) {
            autoToolchain = truthy(value);
        }
        value = System.getenv("CODEFORGE_CONTEXT_SIZE");
        if (value != null && !value.isBlank()) {
            try {
                contextSize = Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        value = System.getenv("CODEFORGE_PARALLEL");
        if (value != null && !value.isBlank()) {
            try {
                parallelSlots = Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void clamp() {
        maxEntries = Math.max(100, Math.min(50000, maxEntries));
        retrievalLimit = Math.max(0, Math.min(20, retrievalLimit));
        onlineTimeoutMs = Math.max(2000, Math.min(60000, onlineTimeoutMs));
        maxResults = Math.max(1, Math.min(6, maxResults));
        refreshHours = Math.max(1, Math.min(24 * 30, refreshHours));
        contextSize = Math.max(2048, Math.min(262144, contextSize));
        parallelSlots = Math.max(1, Math.min(8, parallelSlots));
        researchMode = researchMode.toLowerCase(Locale.ROOT);
        if (!researchMode.equals("off") && !researchMode.equals("auto")
                && !researchMode.equals("always")) {
            researchMode = "auto";
        }
        jurisdiction = jurisdiction.trim().toUpperCase(Locale.ROOT);
        if (jurisdiction.isEmpty()) {
            jurisdiction = "auto";
        }
        onlineLlmUrl = onlineLlmUrl.trim();
        while (onlineLlmUrl.endsWith("/")) {
            onlineLlmUrl = onlineLlmUrl.substring(0, onlineLlmUrl.length() - 1);
        }
        if (onlineLlmUrl.isBlank()) {
            onlineLlmUrl = "https://api.deepseek.com";
        }
        if (onlineLlmModel.isBlank()) {
            onlineLlmModel = "deepseek-chat";
        }
        onlineLlmKey = onlineLlmKey.trim();
    }

    private static boolean truthy(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("1") || lower.equals("true") || lower.equals("yes")
                || lower.equals("on");
    }

    public boolean memoryEnabled() {
        ensure();
        return memoryEnabled;
    }

    public boolean autoCapture() {
        ensure();
        return autoCapture;
    }

    public int maxEntries() {
        ensure();
        return maxEntries;
    }

    public int retrievalLimit() {
        ensure();
        return retrievalLimit;
    }

    public boolean onlineEnabled() {
        ensure();
        return onlineEnabled;
    }

    public String researchMode() {
        ensure();
        return researchMode;
    }

    public String searchEndpoint() {
        ensure();
        return searchEndpoint;
    }

    public int onlineTimeoutMs() {
        ensure();
        return onlineTimeoutMs;
    }

    public int maxResults() {
        ensure();
        return maxResults;
    }

    public boolean complianceEnabled() {
        ensure();
        return complianceEnabled;
    }

    public String jurisdiction() {
        ensure();
        return jurisdiction;
    }

    public boolean onlineRefresh() {
        ensure();
        return onlineRefresh;
    }

    public boolean autoToolchain() {
        ensure();
        return autoToolchain;
    }

    public int refreshHours() {
        ensure();
        return refreshHours;
    }

    public Path stateDir() {
        return root.resolve("memory");
    }

    public boolean onlineLlmConfigured() {
        ensure();
        return onlineLlmEnabled && !onlineLlmKey.isBlank();
    }

    public String onlineLlmUrl() {
        ensure();
        return onlineLlmUrl;
    }

    public String onlineLlmModel() {
        ensure();
        return onlineLlmModel;
    }

    public int contextSize() {
        ensure();
        return contextSize;
    }

    public int parallelSlots() {
        ensure();
        return parallelSlots;
    }
}
