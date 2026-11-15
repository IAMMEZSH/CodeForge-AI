package codeforge.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiHandler implements HttpHandler {
    public static final String VERSION = "1.0.2";
    private static final long MAX_BODY = 8L * 1024 * 1024;

    private final Path root;
    private final Path webRoot;
    private final CoreBridge core;
    private final ModelManager models;
    private final RuntimeManager runtime;
    private final NativeTools nativeTools;
    private final AppSettings settings;
    private final MemoryStore memory;
    private final ProjectIndex projectIndex;
    private final OnlineService online;
    private final ComplianceService compliance;
    private final ToolchainManager toolchains;
    private final ExtendedRunner extendedRunner;
    private final FileService files;
    private final DesktopIntegration desktop;
    private final ProjectExporter projectExporter;
    private final Runnable shutdownAction;
    private final Instant startedAt = Instant.now();
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "status-cache");
                thread.setDaemon(true);
                return thread;
            });

    private Map<String, Object> cachedStatus;
    private long cachedStatusAt;
    private volatile String lastJurisdiction = "";
    private final Map<String, ChatProgress> activeChats = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> chatCancels = new ConcurrentHashMap<>();
    private volatile double localGenerationRate = 8.5;

    private static final class ChatStep {
        final String id;
        final String label;
        final long atMs;
        volatile String detail;
        volatile String status;

        ChatStep(String id, String label, String detail, String status, long atMs) {
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.status = status;
            this.atMs = atMs;
        }
    }

    private static final class ChatProgress {
        final long startedMs;
        final List<ChatStep> steps = new CopyOnWriteArrayList<>();
        volatile String route;
        volatile int estimatedMs;
        volatile long genStartedMs;
        volatile int predictedTokens;
        volatile int generatedTokens;
        volatile int contextTokens;
        volatile int maxTokens = 2048;
        volatile double tokensPerSecond;
        volatile long slotCheckedAt;
        volatile long lastSampleMs;
        volatile int lastSampleTokens;
        volatile int shownPercent;
        volatile String phase = "prepare";
        volatile boolean completed;

        ChatProgress(long startedMs, String route, int estimatedMs) {
            this.startedMs = startedMs;
            this.route = route;
            this.estimatedMs = estimatedMs;
        }
    }

    public ApiHandler(Path root, CoreBridge core, ModelManager models,
                      RuntimeManager runtime, NativeTools nativeTools,
                      AppSettings settings, MemoryStore memory, ProjectIndex projectIndex,
                      OnlineService online, ComplianceService compliance,
                      Runnable shutdownAction) {
        this.root = root;
        this.webRoot = root.resolve("server-java").resolve("web");
        this.core = core;
        this.models = models;
        this.runtime = runtime;
        this.nativeTools = nativeTools;
        this.settings = settings;
        this.memory = memory;
        this.projectIndex = projectIndex;
        this.online = online;
        this.compliance = compliance;
        this.toolchains = new ToolchainManager(root);
        this.extendedRunner = new ExtendedRunner(root, toolchains);
        this.files = new FileService(root);
        this.desktop = new DesktopIntegration(root, files);
        this.projectExporter = new ProjectExporter(root, files, desktop);
        this.shutdownAction = shutdownAction;
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                coreStatus(false);
            } catch (Exception ignored) {
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase();
            if (path.startsWith("/api/")) {
                handleApi(exchange, method, path);
                return;
            }
            if (path.startsWith("/preview/")) {
                servePreview(exchange, path);
                return;
            }
            serveStatic(exchange, path);
        } catch (Exception ex) {
            try {
                sendJson(exchange, 500, Json.map("ok", false,
                        "error", "Internal server error: " + ex.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String method, String path)
            throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        if (!"GET".equals(method)) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null ||
                    !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                sendJson(exchange, 415, Json.map("ok", false,
                        "error", "Content-Type must be application/json"));
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null) {
                String host = exchange.getRequestHeaders().getFirst("Host");
                String expected = "http://" + (host == null ? "127.0.0.1:8765" : host);
                if (!expected.equalsIgnoreCase(origin)) {
                    sendJson(exchange, 403, Json.map("ok", false,
                            "error", "Cross-origin request rejected"));
                    return;
                }
            }
        }
        switch (path) {
            case "/api/health":
                sendJson(exchange, 200, Json.map(
                        "ok", true,
                        "service", "codeforge-java-server",
                        "version", VERSION,
                        "uptime_seconds", Duration.between(startedAt, Instant.now()).toSeconds(),
                        "platform", System.getProperty("os.name"),
                        "java", System.getProperty("java.version")));
                return;
            case "/api/status": {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("ok", true);
                status.put("server", Json.map(
                        "version", VERSION,
                        "root", root.toString(),
                        "platform", System.getProperty("os.name"),
                        "arch", System.getProperty("os.arch"),
                        "java", System.getProperty("java.version"),
                        "uptime_seconds", Duration.between(startedAt, Instant.now()).toSeconds()));
                status.put("core", coreStatus(false));
                status.put("native", nativeTools.sysinfo());
                status.put("llm_runtime", runtime.status());
                status.put("models", models.summary());
                status.put("downloads", models.jobsSnapshot());
                status.put("runtime", models.runtimeFiles());
                status.put("memory", memory.stats());
                status.put("workspace", projectIndex.stats());
                status.put("online", online.status());
                status.put("brain", Json.map(
                        "online_configured", settings.onlineLlmConfigured(),
                        "online_model", settings.onlineLlmModel(),
                        "online_url", settings.onlineLlmUrl()));
                status.put("compliance", Json.map(
                        "enabled", settings.complianceEnabled(),
                        "baseline_enforced", true,
                        "jurisdiction", lastJurisdiction.isBlank()
                                ? settings.jurisdiction() : lastJurisdiction,
                        "configured", settings.jurisdiction(),
                        "online_refresh", settings.onlineRefresh()));
                sendJson(exchange, 200, status);
                return;
            }
            case "/api/chat": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                sendJson(exchange, 200, chat(request));
                return;
            }
            case "/api/chat/progress": {
                sendJson(exchange, 200, chatProgress(query.getOrDefault("request_id", "")));
                return;
            }
            case "/api/chat/cancel": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                String id = Json.str(request, "request_id", "").trim();
                AtomicBoolean flag = chatCancels.get(id);
                if (flag != null) {
                    flag.set(true);
                }
                boolean coreStopped = core.cancel(id);
                sendJson(exchange, 200, Json.map(
                        "ok", true,
                        "request_id", id,
                        "pending", flag != null,
                        "core_stopped", coreStopped));
                return;
            }
            case "/api/debug": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                sendJson(exchange, 200, debug(request));
                return;
            }
            case "/api/memory": {
                handleMemory(exchange, method, query);
                return;
            }
            case "/api/memory/delete":
            case "/api/memory/clear": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                Map<String, Object> response = path.equals("/api/memory/delete")
                        ? memory.delete(Json.str(request, "id", ""))
                        : memory.clear();
                sendJson(exchange, 200, response);
                return;
            }
            case "/api/workspace": {
                if ("GET".equals(method)) {
                    String text = query.getOrDefault("q", "");
                    int limit = query.containsKey("limit")
                            ? parseInt(query.get("limit"), 3) : 3;
                    Map<String, Object> result = text.isBlank()
                            ? new LinkedHashMap<>(projectIndex.stats())
                            : projectIndex.query(text, limit);
                    result.put("ok", true);
                    sendJson(exchange, 200, result);
                } else {
                    requirePost(method);
                    sendJson(exchange, 200, projectIndex.refresh());
                }
                return;
            }
            case "/api/online": {
                boolean probe = !"0".equals(query.getOrDefault("probe", "1"));
                Map<String, Object> result = new LinkedHashMap<>(online.status());
                if (probe) {
                    result.put("online", online.online());
                    result.put("geo", online.geo());
                }
                sendJson(exchange, 200, result);
                return;
            }
            case "/api/online/research": {
                String text = query.getOrDefault("q", query.getOrDefault("query", ""));
                sendJson(exchange, 200, online.research(text));
                return;
            }
            case "/api/compliance": {
                String[] detected = compliance.detect(query.get("jurisdiction"));
                Map<String, Object> result = new LinkedHashMap<>(
                        compliance.status(detected[0]));
                result.put("jurisdiction_source", detected[1]);
                result.put("available", compliance.jurisdictions());
                result.put("online", online.status());
                sendJson(exchange, 200, result);
                return;
            }
            case "/api/compliance/refresh": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                String[] detected = compliance.detect(Json.str(request, "jurisdiction", ""));
                sendJson(exchange, 200, online.refreshLegalSync(
                        detected[0], compliance.countryFor(detected[0])));
                return;
            }
            case "/api/run": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                String source = Json.str(request, "code", "");
                ComplianceService.Verdict verdict = compliance.screen(source, "run-input",
                        Json.str(request, "jurisdiction", ""));
                compliance.log(verdict, "run-input", source);
                if ("block".equals(verdict.verdict)) {
                    Map<String, Object> blocked = new LinkedHashMap<>();
                    blocked.put("ok", false);
                    blocked.put("blocked", true);
                    blocked.put("error", "Blocked by the universal compliance rule");
                    blocked.put("jurisdiction", verdict.jurisdiction);
                    blocked.put("notice", compliance.refusal(verdict));
                    sendJson(exchange, 200, blocked);
                    return;
                }
                LanguageSupport.Language language = LanguageSupport.normalize(
                        Json.str(request, "language", "auto"),
                        Json.str(request, "filename", ""));
                Map<String, Object> response;
                if (language != null && extendedRunner.handles(language)) {
                    response = extendedRunner.run(request);
                } else {
                    response = core.call("run", request, 180);
                }
                response.put("jurisdiction", verdict.jurisdiction);
                sendJson(exchange, 200, response);
                return;
            }
            case "/api/languages": {
                sendJson(exchange, 200, toolchains.summary());
                return;
            }
            case "/api/languages/install": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                String language = Json.str(request, "language",
                        Json.str(request, "runtime", ""));
                if (language.isBlank()) {
                    sendJson(exchange, 200, Json.map("ok", false,
                            "error", "A language or runtime id is required"));
                    return;
                }
                sendJson(exchange, 200, toolchains.install(language));
                return;
            }
            case "/api/files/write": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                sendJson(exchange, 200, files.write(request));
                return;
            }
            case "/api/files/write-batch": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                sendJson(exchange, 200, files.writeBatch(request));
                return;
            }
            case "/api/files/reveal": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                sendJson(exchange, 200, files.reveal(request));
                return;
            }
            case "/api/desktop/install": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                sendJson(exchange, 200, desktop.install(request));
                return;
            }
            case "/api/project/place": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                sendJson(exchange, 200, projectExporter.place(request));
                return;
            }
            case "/api/selftest": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                Map<String, Object> response = core.call("selftest", request, 300);
                sendJson(exchange, 200, response);
                return;
            }
            case "/api/models": {
                Map<String, Object> result = models.summary();
                result.put("ok", true);
                result.put("runtime", models.runtimeFiles());
                result.put("downloads", models.jobsSnapshot());
                result.put("llm_runtime", runtime.status());
                sendJson(exchange, 200, result);
                return;
            }
            case "/api/models/download": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                String id = Json.str(request, "id", "");
                Map<String, Object> response = models.startModelDownload(id);
                sendJson(exchange, 200, response);
                return;
            }
            case "/api/models/resolve": {
                String id = query.getOrDefault("id", "");
                sendJson(exchange, 200, models.resolveModel(id));
                return;
            }
            case "/api/runtime/install": {
                requirePost(method);
                sendJson(exchange, 200, models.startRuntimeInstall());
                return;
            }
            case "/api/runtime/start": {
                requirePost(method);
                Map<String, Object> request = readJsonObject(exchange);
                Map<String, Object> response = runtime.start(
                        Json.str(request, "model_id", ""),
                        Json.integer(request, "context_size", settings.contextSize()),
                        Json.integer(request, "parallel", settings.parallelSlots()));
                sendJson(exchange, 200, response);
                return;
            }
            case "/api/runtime/stop": {
                requirePost(method);
                cachedStatus = null;
                sendJson(exchange, 200, runtime.stop());
                return;
            }
            case "/api/runtime/logs": {
                int lines = query.containsKey("lines")
                        ? parseInt(query.get("lines"), 120) : 120;
                sendJson(exchange, 200, runtime.logs(lines));
                return;
            }
            case "/api/downloads":
                sendJson(exchange, 200, Json.map("ok", true,
                        "jobs", models.jobsSnapshot()));
                return;
            case "/api/shutdown": {
                requirePost(method);
                sendJson(exchange, 200, Json.map("ok", true,
                        "message", "Server is shutting down"));
                Thread shutdownThread = new Thread(shutdownAction, "shutdown-request");
                shutdownThread.setDaemon(true);
                shutdownThread.start();
                return;
            }
            default:
                sendJson(exchange, 404, Json.map("ok", false,
                        "error", "Unknown endpoint: " + path));
        }
    }

    private Map<String, Object> coreStatus(boolean refresh) {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (!refresh && cachedStatus != null && now - cachedStatusAt < 8000) {
                return cachedStatus;
            }
            Map<String, Object> status = core.available()
                    ? core.call("status", Json.map(), 60)
                    : Json.map("ok", false,
                    "error", "C++ main core is not built. Run the build script to generate native/bin/CodeForgeCore.");
            cachedStatus = status;
            cachedStatusAt = System.currentTimeMillis();
            return status;
        }
    }

    private void handleMemory(HttpExchange exchange, String method, Map<String, String> query)
            throws IOException {
        if ("GET".equals(method)) {
            String text = query.getOrDefault("q", "");
            int limit = query.containsKey("limit")
                    ? parseInt(query.get("limit"), 20) : 20;
            Map<String, Object> result;
            if (text.isBlank()) {
                result = new LinkedHashMap<>(memory.stats());
                result.put("recent", memory.recent(limit));
            } else {
                result = memory.query(text, limit);
            }
            result.put("ok", true);
            sendJson(exchange, 200, result);
            return;
        }
        requirePost(method);
        Map<String, Object> request = readJsonObject(exchange);
        sendJson(exchange, 200, memory.add(
                Json.str(request, "text", ""),
                Json.str(request, "kind", "note"),
                Json.str(request, "source", "manual"),
                Json.str(request, "jurisdiction", "")));
    }

    private Map<String, Object> chat(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        String requestId = Json.str(request, "request_id", "").trim();
        if (requestId.isEmpty() || requestId.length() > 80) {
            requestId = UUID.randomUUID().toString();
        }
        List<Object> messages = Json.asList(request.get("messages"));
        String userText = lastUserMessage(messages);
        String conversation = conversationText(messages, 4000);
        LanguageSupport.Language requestedLanguage = null;
        for (LanguageSupport.Language candidate : LanguageSupport.detectRequested(userText)) {
            if (candidate.preview || candidate.executable()) {
                requestedLanguage = candidate;
                break;
            }
        }
        ComplianceService.Verdict verdict = compliance.screen(userText, "chat-input",
                Json.str(request, "jurisdiction", ""));
        compliance.log(verdict, "chat-input", userText);
        String code = verdict.jurisdiction;
        lastJurisdiction = code;
        Map<String, Object> meta = baseMeta(verdict);
        if ("block".equals(verdict.verdict)) {
            meta.put("elapsed_ms", System.currentTimeMillis() - started);
            return Json.map(
                    "ok", false,
                    "blocked", true,
                    "reply", compliance.refusal(verdict),
                    "error", "Blocked by the universal compliance rule",
                    "jurisdiction", code,
                    "sources", List.of(),
                    "request_id", requestId,
                    "meta", meta);
        }
        String routeHint = settings.onlineLlmConfigured() ? "online" : "local";
        Map<String, Object> toolchain = null;
        if (requestedLanguage != null) {
            toolchain = toolchains.ensure(requestedLanguage.runtime, settings.autoToolchain());
            toolchain.put("language", requestedLanguage.id);
            toolchain.put("language_name", requestedLanguage.name);
            toolchain.put("auto_install", settings.autoToolchain());
        }
        ChatProgress progress = new ChatProgress(started, routeHint,
                5000 + estimateGenerationMs(routeHint, userText, conversation, userText.length()));
        progress.steps.add(new ChatStep("screen", "Compliance screen",
                verdict.verdict + " · " + code, "done", System.currentTimeMillis() - started));
        if (requestedLanguage != null) {
            String state = Json.str(toolchain, "state", "missing");
            String detail;
            if ("ready".equals(state)) {
                detail = Json.str(toolchain, "version", "ready");
            } else if ("downloading".equals(state) || "extracting".equals(state)
                    || "installing".equals(state) || "verifying".equals(state)) {
                Object percent = toolchain.get("progress");
                detail = state + (percent == null ? "" : " · " + percent + "%");
            } else {
                detail = "not installed · " + Json.str(toolchain, "hint", "");
            }
            progress.steps.add(new ChatStep("toolchain",
                    "Prepare " + requestedLanguage.name + " toolchain",
                    detail, "done", System.currentTimeMillis() - started));
        }
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeChats.put(requestId, progress);
        chatCancels.put(requestId, cancelFlag);
        try {
            String route = settings.onlineLlmConfigured() && online.online() ? "online" : "local";
            progress.route = route;
            List<MemoryStore.Entry> hits = settings.memoryEnabled()
                    ? memory.search(userText, settings.retrievalLimit()) : List.of();
            List<ProjectIndex.Doc> workspaceHits = settings.memoryEnabled()
                    ? projectIndex.search(userText, 3) : List.of();
            addStep(progress, "memory", "Recall persistent memory",
                    hits.size() + " hits / " + memory.stats().get("entries") + " stored");
            addStep(progress, "workspace", "Scan workspace index",
                    workspaceHits.size() + " matches / " + projectIndex.stats().get("files") + " files");
            if (cancelFlag.get()) {
                return cancelledChat(requestId, code, meta, started);
            }
            progress.phase = "research";
            boolean wantsResearch = online.shouldResearch(userText);
            addStep(progress, "research", "Research current sources",
                    wantsResearch ? "searching the live web…" : "not required for this question",
                    wantsResearch ? "active" : "done");
            Map<String, Object> research = null;
            if (wantsResearch) {
                research = online.research(userText);
            }
            int researchCount = research == null ? 0 : Json.asList(research.get("results")).size();
            if (wantsResearch) {
                String sourceName = research == null ? "" : String.valueOf(research.get("source"));
                if (sourceName.isBlank() || "null".equals(sourceName)) {
                    sourceName = "live web";
                }
                finishStep(progress, "research",
                        research != null && Boolean.TRUE.equals(research.get("ok"))
                                ? sourceName + " · " + researchCount + " results"
                                : "no usable source · continuing with local knowledge");
            }
            if (cancelFlag.get()) {
                return cancelledChat(requestId, code, meta, started);
            }
            request.put("route", route);
            StringBuilder context = new StringBuilder(compliance.promptContext(code,
                    settings.complianceEnabled()
                            ? online.legalContext(code, compliance.countryFor(code)) : ""));
            if (requestedLanguage != null) {
                context.append('\n').append(LanguageSupport.directive(requestedLanguage));
            }
            if ("caution".equals(verdict.verdict) && !verdict.reason.isBlank()) {
                context.append("\nCOMPLIANCE CAUTION: ").append(verdict.reason).append('\n');
            }
            if (research != null && Boolean.TRUE.equals(research.get("ok"))) {
                context.append('\n').append(research.get("context"));
            }
            String memoryContext = memory.contextFor(hits);
            if (!memoryContext.isBlank()) {
                context.append('\n').append(memoryContext).append('\n');
            }
            String workspaceContext = projectIndex.contextFor(workspaceHits);
            if (!workspaceContext.isBlank()) {
                context.append('\n').append(workspaceContext).append('\n');
            }
            context.append("\nAnswer in the user's language. Cite URLs when using online facts, ")
                    .append("and only URLs that literally appear in the context above. ")
                    .append("Cite local files by relative path. Never invent repository links, ")
                    .append("APIs, laws or version numbers.\n");
            request.put("context", cap(context.toString(), 9000));
            addStep(progress, "context", "Assemble context", context.length() + " chars");
            if (cancelFlag.get()) {
                return cancelledChat(requestId, code, meta, started);
            }
            progress.phase = "generate";
            progress.genStartedMs = System.currentTimeMillis();
            progress.predictedTokens = estimateGenerationTokens(route, userText, conversation);
            long elapsedBeforeGenerate = progress.genStartedMs - started;
            progress.estimatedMs = (int) Math.min(300000,
                    elapsedBeforeGenerate + estimateGenerationMs(route, userText, conversation, context.length()));
            addStep(progress, "generate", "Generate answer",
                    "online".equals(route) ? "hosted brain" : "on-device model", "active");
            Map<String, Object> response = core.call("chat", request, 900, requestId);
            long genElapsed = Math.max(0, System.currentTimeMillis() - progress.genStartedMs);
            finishStep(progress, "generate", route + " · " + (genElapsed / 1000) + "s");
            if (Boolean.TRUE.equals(response.get("ok"))) {
                response.put("model", friendlyModelName(Json.str(response, "model", "")));
            }
            if (cancelFlag.get() || Boolean.TRUE.equals(response.get("cancelled"))) {
                return cancelledChat(requestId, code, meta, started);
            }
            meta.put("memory", Json.map(
                    "enabled", settings.memoryEnabled(),
                    "entries", memory.stats().get("entries"),
                    "hits", hits.size()));
            meta.put("workspace", Json.map(
                    "enabled", settings.memoryEnabled(),
                    "files", projectIndex.stats().get("files"),
                    "hits", workspaceHits.size()));
            String actualRoute = Json.str(response, "route", route);
            meta.put("brain", Json.map(
                    "requested", route,
                    "route", actualRoute,
                    "online_configured", settings.onlineLlmConfigured(),
                    "online_model", settings.onlineLlmModel(),
                    "online_url", settings.onlineLlmUrl(),
                    "route_error", Json.str(response, "route_error", "")));
            meta.put("online", Json.map(
                    "used", research != null && Boolean.TRUE.equals(research.get("ok")),
                    "online", research != null ? research.get("online")
                            : online.status().get("online"),
                    "source", research == null ? "" : research.get("source"),
                    "results", researchCount,
                    "error", research == null ? "" : research.get("error")));
            meta.put("context_chars", context.length());
            if (requestedLanguage != null) {
                Map<String, Object> latest = toolchains.ensure(requestedLanguage.runtime, false);
                latest.put("language", requestedLanguage.id);
                latest.put("language_name", requestedLanguage.name);
                latest.put("auto_install", settings.autoToolchain());
                meta.put("toolchain", latest);
                meta.put("requested_language", requestedLanguage.id);
                meta.put("requested_language_name", requestedLanguage.name);
            }
            progress.phase = "verify";
            addStep(progress, "verify", "Screen output", "checking the reply…", "active");
            if (Boolean.TRUE.equals(response.get("ok"))) {
                String reply = Json.str(response, "reply", "");
                ComplianceService.Verdict output = compliance.screenOutput(reply, code);
                if ("block".equals(output.verdict)) {
                    compliance.log(output, "chat-output", reply);
                    response.put("ok", false);
                    response.put("blocked", true);
                    response.put("reply", compliance.refusal(output));
                    response.put("error", "Model output withheld by the universal compliance rule");
                    meta.put("verdict", "block");
                    meta.put("output_flags", output.flags);
                    finishStep(progress, "verify", "blocked by the universal rule");
                } else {
                    finishStep(progress, "verify", "passed");
                    if (cancelFlag.get()) {
                        return cancelledChat(requestId, code, meta, started);
                    }
                    String model = Json.str(response, "model", "");
                    memory.rememberExchange(userText, reply, model, code);
                    if ("online".equals(actualRoute) && settings.memoryEnabled()) {
                        memory.add("Online lesson (" + (model.isBlank() ? "online model" : model)
                                        + ") Q: " + cap(userText, 320)
                                        + " A: " + cap(reply, 900),
                                "lesson", "online", code);
                    }
                }
            } else {
                finishStep(progress, "verify", "skipped: no usable output");
            }
            meta.put("elapsed_ms", System.currentTimeMillis() - started);
            meta.put("estimated_ms", progress.estimatedMs);
            response.put("sources", sourcesOf(research));
            response.put("jurisdiction", code);
            response.put("request_id", requestId);
            response.put("meta", meta);
            progress.completed = true;
            return response;
        } finally {
            activeChats.remove(requestId, progress);
            chatCancels.remove(requestId, cancelFlag);
            core.clearCancellation(requestId);
            if (progress.completed) {
                recordGenerationRate(progress);
            }
        }
    }

    private Map<String, Object> chatProgress(String requestId) {
        String id = requestId == null ? "" : requestId.trim();
        ChatProgress progress = activeChats.get(id);
        if (progress == null) {
            return Json.map("ok", true, "active", false);
        }
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, now - progress.startedMs);
        boolean generating = "generate".equals(progress.phase);
        long genElapsed = progress.genStartedMs <= 0 ? 0 : Math.max(0, now - progress.genStartedMs);
        if (generating && "local".equals(progress.route) && genElapsed > 1200
                && now - progress.slotCheckedAt >= 900) {
            progress.slotCheckedAt = now;
            Map<String, Object> slot = runtime.slotStats();
            if (Json.bool(slot, "processing", false)) {
                int prompt = Json.integer(slot, "n_prompt_tokens", 0);
                int max = Json.integer(slot, "max_tokens", 0);
                int decoded = Json.integer(slot, "n_decoded", 0);
                if (prompt > 0) {
                    progress.contextTokens = prompt;
                }
                if (max > 0) {
                    progress.maxTokens = max;
                }
                if (decoded > progress.generatedTokens) {
                    if (progress.lastSampleMs > 0) {
                        long sampleMs = Math.max(1, now - progress.lastSampleMs);
                        int sampleTokens = decoded - progress.lastSampleTokens;
                        if (sampleTokens > 0) {
                            double instant = sampleTokens * 1000.0 / sampleMs;
                            progress.tokensPerSecond = progress.tokensPerSecond > 0
                                    ? progress.tokensPerSecond * 0.55 + instant * 0.45
                                    : instant;
                        }
                    }
                    progress.lastSampleMs = now;
                    progress.lastSampleTokens = decoded;
                    progress.generatedTokens = decoded;
                }
                ChatStep generate = findStep(progress, "generate");
                if (generate != null) {
                    if (progress.generatedTokens > 0) {
                        generate.detail = tokensDetail(progress);
                    } else if (progress.contextTokens > 0) {
                        generate.detail = "reading " + progress.contextTokens
                                + "-token context · on-device model";
                    }
                }
            }
        }
        long remaining;
        if (generating && progress.tokensPerSecond > 0 && progress.generatedTokens > 0) {
            int cap = progress.maxTokens > 0 ? progress.maxTokens : 2048;
            int expected = Math.min(cap, Math.max(progress.predictedTokens,
                    progress.generatedTokens + 160));
            remaining = Math.max(0, (long) ((expected - progress.generatedTokens)
                    / progress.tokensPerSecond * 1000.0));
            if (remaining <= 0) {
                remaining = 1000;
            }
        } else {
            remaining = Math.max(0, progress.estimatedMs - elapsed);
        }
        long effectiveEstimate = Math.max(elapsed, elapsed + remaining);
        int percent = (int) Math.max(1, Math.min(96,
                Math.round(100.0 * elapsed / Math.max(1, effectiveEstimate))));
        percent = Math.max(progress.shownPercent, percent);
        progress.shownPercent = percent;
        List<Object> steps = new ArrayList<>();
        for (ChatStep step : progress.steps) {
            steps.add(Json.map(
                    "id", step.id,
                    "label", step.label,
                    "detail", step.detail,
                    "status", step.status,
                    "at_ms", step.atMs));
        }
        return Json.map(
                "ok", true,
                "active", true,
                "request_id", id,
                "phase", progress.phase,
                "route", progress.route,
                "elapsed_ms", elapsed,
                "estimated_ms", effectiveEstimate,
                "remaining_ms", Math.max(0, effectiveEstimate - elapsed),
                "percent", percent,
                "generation", Json.map(
                        "tokens", progress.generatedTokens,
                        "predicted", progress.predictedTokens,
                        "max_tokens", progress.maxTokens,
                        "tokens_per_second", Math.round(progress.tokensPerSecond * 10) / 10.0,
                        "context_tokens", progress.contextTokens,
                        "measured", progress.tokensPerSecond > 0),
                "steps", steps);
    }

    private static ChatStep findStep(ChatProgress progress, String id) {
        for (ChatStep step : progress.steps) {
            if (step.id.equals(id)) {
                return step;
            }
        }
        return null;
    }

    private static String tokensDetail(ChatProgress progress) {
        String rate = progress.tokensPerSecond > 0
                ? String.format(Locale.ROOT, "%.1f", progress.tokensPerSecond) : "0.0";
        return progress.generatedTokens + " tokens · " + rate + " tok/s · on-device model";
    }

    private static void addStep(ChatProgress progress, String id, String label, String detail) {
        addStep(progress, id, label, detail, "done");
    }

    private static void addStep(ChatProgress progress, String id, String label,
                                String detail, String status) {
        progress.steps.add(new ChatStep(id, label, detail, status,
                System.currentTimeMillis() - progress.startedMs));
    }

    private static void finishStep(ChatProgress progress, String id, String detail) {
        for (ChatStep step : progress.steps) {
            if (step.id.equals(id)) {
                step.detail = detail;
                step.status = "done";
            }
        }
    }

    private Map<String, Object> cancelledChat(String requestId, String code,
                                              Map<String, Object> meta, long started) {
        meta.put("elapsed_ms", System.currentTimeMillis() - started);
        meta.put("cancelled", true);
        return Json.map(
                "ok", false,
                "cancelled", true,
                "reply", "",
                "error", "Interrupted by user",
                "jurisdiction", code,
                "sources", List.of(),
                "request_id", requestId,
                "meta", meta);
    }

    private int estimateGenerationTokens(String route, String text, String conversation) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String all = (lower + " " + (conversation == null ? "" : conversation.toLowerCase(Locale.ROOT)));
        boolean codeAnswer = lower.matches("(?s).*(写|实现|生成|创建|代码|程序|示例|函数|类|脚本|重构|修复|"
                + "write|implement|build|create|code|program|script|class|function|example|refactor|debug).*");
        boolean bigAnswer = all.matches("(?s).*(完整|项目|系统|线程池|框架|模块|游戏|服务器|客户端|编译器|解释器|"
                + "full|project|system|thread.?pool|framework|module|game|server|client|compiler|interpreter).*");
        double base;
        if ("online".equals(route)) {
            base = codeAnswer ? (bigAnswer ? 900 : 420) : 260;
        } else {
            base = codeAnswer ? (bigAnswer ? 1500 : 400) : 300;
        }
        if (!bigAnswer && lower.matches("(?s).*(简短|简单|最小|简要|只要|仅|short|brief|minimal|simple|concise).*")) {
            base *= 0.65;
        } else if (lower.matches("(?s).*(详细|完整|全面|深入|detailed|complete|thorough).*")) {
            base *= 1.2;
        }
        double scale = Math.max(0.72, Math.min(1.25, lower.length() / 260.0));
        return (int) Math.max(160, Math.min(2048, base * scale));
    }

    private int estimateGenerationMs(String route, String text, String conversation, int contextChars) {
        int tokens = estimateGenerationTokens(route, text, conversation);
        double rate = "online".equals(route) ? 55.0 : localGenerationRate;
        double prefillMs = "online".equals(route) ? 800
                : Math.min(35000, Math.max(0, contextChars) / 450.0 * 1000.0);
        return (int) Math.max(5000, Math.min(240000, 2500 + prefillMs + tokens / rate * 1000.0));
    }

    private void recordGenerationRate(ChatProgress progress) {
        if (progress == null || progress.genStartedMs <= 0 || !"local".equals(progress.route)) {
            return;
        }
        long genElapsed = System.currentTimeMillis() - progress.genStartedMs;
        if (genElapsed < 1500 || progress.generatedTokens < 8) {
            return;
        }
        double measured = progress.generatedTokens * 1000.0 / genElapsed;
        if (measured < 1.0 || measured > 200.0) {
            return;
        }
        synchronized (this) {
            localGenerationRate = localGenerationRate * 0.7 + measured * 0.3;
        }
    }

    private Map<String, Object> debug(Map<String, Object> request) {
        String source = Json.str(request, "code", "");
        ComplianceService.Verdict verdict = compliance.screen(source, "debug-input",
                Json.str(request, "jurisdiction", ""));
        compliance.log(verdict, "debug-input", source);
        String code = verdict.jurisdiction;
        lastJurisdiction = code;
        Map<String, Object> meta = baseMeta(verdict);
        if ("block".equals(verdict.verdict)) {
            return Json.map(
                    "ok", false,
                    "success", false,
                    "blocked", true,
                    "error", "Blocked by the universal compliance rule",
                    "final_code", "",
                    "iterations", List.of(),
                    "jurisdiction", code,
                    "notice", compliance.refusal(verdict),
                    "meta", meta);
        }
        int iterations = Json.integer(request, "iterations", 3);
        int timeout = 240 + Math.max(1, Math.min(8, iterations)) * 300;
        List<MemoryStore.Entry> hits = settings.memoryEnabled()
                ? memory.search(Json.str(request, "language", "auto") + " "
                        + Json.str(request, "filename", ""),
                Math.min(3, settings.retrievalLimit()))
                : List.of();
        List<ProjectIndex.Doc> workspaceHits = settings.memoryEnabled()
                ? projectIndex.search(Json.str(request, "filename", "") + " "
                        + Json.str(request, "language", ""), 2)
                : List.of();
        String route = settings.onlineLlmConfigured() && online.online() ? "online" : "local";
        request.put("route", route);
        StringBuilder context = new StringBuilder(compliance.promptContext(code,
                settings.complianceEnabled()
                        ? online.legalContext(code, compliance.countryFor(code)) : ""));
        if ("caution".equals(verdict.verdict) && !verdict.reason.isBlank()) {
            context.append("\nCOMPLIANCE CAUTION: ").append(verdict.reason).append('\n');
        }
        String memoryContext = memory.contextFor(hits);
        if (!memoryContext.isBlank()) {
            context.append('\n').append(memoryContext).append('\n');
        }
        String workspaceContext = projectIndex.contextFor(workspaceHits);
        if (!workspaceContext.isBlank()) {
            context.append('\n').append(workspaceContext).append('\n');
        }
        context.append("\nIf the source implements malicious or clearly illegal behavior, ")
                .append("refuse and explain instead of repairing it.\n");
        request.put("context", cap(context.toString(), 9000));
        Map<String, Object> response = core.call("debug", request, timeout);
        if (Boolean.TRUE.equals(response.get("success")) && settings.memoryEnabled()) {
            int steps = Json.asList(response.get("iterations")).size();
            memory.add("Debug session succeeded (" + Json.str(request, "language", "auto")
                            + ", " + Json.str(request, "filename", "main") + ") after "
                            + steps + " iteration(s); final source "
                            + Json.str(response, "final_code", "").length() + " chars.",
                    "note", "debug", code);
        }
        meta.put("memory", Json.map(
                "enabled", settings.memoryEnabled(),
                "entries", memory.stats().get("entries"),
                "hits", hits.size()));
        meta.put("workspace", Json.map(
                "enabled", settings.memoryEnabled(),
                "files", projectIndex.stats().get("files"),
                "hits", workspaceHits.size()));
        meta.put("brain", Json.map(
                "requested", route,
                "route", Json.str(response, "route", route),
                "online_configured", settings.onlineLlmConfigured(),
                "online_model", settings.onlineLlmModel(),
                "online_url", settings.onlineLlmUrl(),
                "route_error", Json.str(response, "route_error", "")));
        response.put("jurisdiction", code);
        response.put("meta", meta);
        return response;
    }

    private static Map<String, Object> baseMeta(ComplianceService.Verdict verdict) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("jurisdiction", verdict.jurisdiction);
        meta.put("jurisdiction_source", verdict.jurisdictionSource);
        meta.put("verdict", verdict.verdict);
        meta.put("flags", verdict.flags);
        if (!verdict.reason.isBlank()) {
            meta.put("reason", verdict.reason);
        }
        return meta;
    }

    private static List<Object> sourcesOf(Map<String, Object> research) {
        List<Object> sources = new ArrayList<>();
        if (research == null) {
            return sources;
        }
        for (Object raw : Json.asList(research.get("results"))) {
            Map<String, Object> item = Json.asObject(raw);
            sources.add(Json.map(
                    "title", Json.str(item, "title", ""),
                    "url", Json.str(item, "url", "")));
        }
        return sources;
    }

    private static String lastUserMessage(List<Object> messages) {
        for (int i = messages.size() - 1; i >= 0; --i) {
            Map<String, Object> message = Json.asObject(messages.get(i));
            if ("user".equals(Json.str(message, "role", ""))) {
                return Json.str(message, "content", "");
            }
        }
        return "";
    }

    private static String friendlyModelName(String model) {
        if (model == null || model.isBlank()) {
            return model;
        }
        String normalized = model.replace('\\', '/');
        for (Object raw : ModelManager.registry()) {
            Map<String, Object> entry = Json.asObject(raw);
            String file = Json.str(entry, "file", "");
            String id = Json.str(entry, "id", "");
            String name = Json.str(entry, "name", "");
            if (name.isBlank()) {
                continue;
            }
            if ((!file.isBlank() && (normalized.equals(file) || normalized.endsWith("/" + file)))
                    || model.equals(id)) {
                return name;
            }
        }
        return model;
    }

    private static String conversationText(List<Object> messages, int limit) {
        StringBuilder text = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0 && text.length() < limit; --i) {
            String content = Json.str(Json.asObject(messages.get(i)), "content", "");
            if (!content.isBlank()) {
                text.append(content).append('\n');
            }
        }
        return text.length() > limit ? text.substring(0, limit) : text.toString();
    }

    private static String cap(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n[context truncated]";
    }

    private void requirePost(String method) throws IOException {
        if (!"POST".equals(method)) {
            throw new IOException("This endpoint only accepts POST");
        }
    }

    private Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        byte[] bytes = readBody(exchange);
        if (bytes.length == 0) {
            return new LinkedHashMap<>();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Exception ex) {
            throw new IOException("Request JSON could not be parsed: " + ex.getMessage());
        }
        Map<String, Object> object = Json.asObject(parsed);
        if (object.isEmpty() && !text.trim().equals("{}")) {
            throw new IOException("Request JSON must be an object");
        }
        return object;
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lengthHeader != null) {
            try {
                long length = Long.parseLong(lengthHeader);
                if (length > MAX_BODY) {
                    throw new IOException("Request body exceeds the 8 MB limit");
                }
            } catch (NumberFormatException ignored) {
            }
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY) {
                    throw new IOException("Request body exceeds the 8 MB limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void servePreview(HttpExchange exchange, String rawPath) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Json.map("ok", false, "error", "Only GET is supported"));
            return;
        }
        String rest = rawPath.substring("/preview/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            sendJson(exchange, 404, Json.map("ok", false, "error", "Preview file not found"));
            return;
        }
        String session = rest.substring(0, slash);
        String relative = rest.substring(slash + 1);
        if (!session.matches("(?i)(?:run|debug)-[a-z0-9._-]{3,80}")) {
            sendJson(exchange, 400, Json.map("ok", false, "error", "Illegal preview session"));
            return;
        }
        if (relative.isEmpty() || relative.contains("..") || relative.contains("\\")
                || relative.startsWith("/")) {
            sendJson(exchange, 400, Json.map("ok", false, "error", "Illegal preview path"));
            return;
        }
        Path sessionDir = root.resolve("workspace").resolve(session).normalize();
        Path file = sessionDir.resolve(relative).normalize();
        if (!file.startsWith(sessionDir) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, Json.map("ok", false, "error", "Preview file not found"));
            return;
        }
        byte[] data = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(file.getFileName().toString()));
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy",
                "sandbox allow-scripts allow-forms allow-modals allow-popups allow-pointer-lock");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private void serveStatic(HttpExchange exchange, String rawPath) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Json.map("ok", false, "error", "Only GET is supported"));
            return;
        }
        String path = rawPath;
        if (path.equals("/") || path.isEmpty()) {
            path = "/console/index.html";
        } else if (path.equals("/console") || path.equals("/console/")) {
            path = "/console/index.html";
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.contains("..")) {
            sendJson(exchange, 400, Json.map("ok", false, "error", "Illegal path"));
            return;
        }
        Path file = webRoot.resolve(relative).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            if (path.equals("/console/index.html")) {
                sendHtml(exchange, 200, fallbackPage());
                return;
            }
            sendJson(exchange, 404, Json.map("ok", false, "error", "Page not found",
                    "path", path));
            return;
        }
        byte[] data = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(file.getFileName().toString()));
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static String fallbackPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>CodeForge AI</title></head><body style=\"background:#05070d;"
                + "color:#e6f1ff;font-family:Segoe UI,sans-serif;padding:40px\">"
                + "<h1>CodeForge AI server is running</h1>"
                + "<p>The web console files are missing. Expected at "
                + "<code>server-java/web/index.html</code>.</p></body></html>";
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');
            try {
                if (index < 0) {
                    result.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                } else {
                    result.put(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload)
            throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendHtml(HttpExchange exchange, int status, String html)
            throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    public void close() {
        sweeper.shutdownNow();
        online.close();
    }
}
