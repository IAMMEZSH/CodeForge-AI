package codeforge.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of the bundled llama.cpp server:
 * start, health probe, log capture and process-tree shutdown.
 */
public final class RuntimeManager {
    public static final int INFERENCE_PORT = 8080;

    private final Path root;
    private final Path pidFile;
    private final ModelManager models;
    private final AppSettings settings;
    private final HttpClient http;
    private Process process;
    private String activeModelId = "";
    private String activeModelPath = "";
    private int activeContextSize;
    private int activeParallelSlots;
    private Instant startedAt;
    private Path logFile;
    private String lastError = "";
    private String activeBackend = "";

    public RuntimeManager(Path root, ModelManager models, AppSettings settings) {
        this.root = root;
        this.pidFile = root.resolve("logs").resolve("llama-server.pid");
        this.models = models;
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public synchronized Map<String, Object> status() {
        boolean alive = process != null && process.isAlive();
        boolean healthy = probeHealth();
        Map<String, Object> result = Json.map(
                "ok", true,
                "running", alive || healthy,
                "managed", alive,
                "healthy", healthy,
                "pid", alive ? process.pid() : 0,
                "port", INFERENCE_PORT,
                "base_url", "http://127.0.0.1:" + INFERENCE_PORT,
                "model_id", activeModelId,
                "model_path", activeModelPath,
                "context_size", activeContextSize > 0 ? activeContextSize : settings.contextSize(),
                "parallel", activeParallelSlots > 0 ? activeParallelSlots : settings.parallelSlots(),
                "active_context_size", activeContextSize,
                "active_parallel", activeParallelSlots,
                "configured_context_size", settings.contextSize(),
                "configured_parallel", settings.parallelSlots(),
                "context_min", AppSettings.MIN_CONTEXT_SIZE,
                "context_max", AppSettings.MAX_CONTEXT_SIZE,
                "parallel_max", AppSettings.MAX_PARALLEL_SLOTS,
                "max_tokens", settings.maxTokens(),
                "max_tokens_min", AppSettings.MIN_MAX_TOKENS,
                "max_tokens_max", AppSettings.MAX_MAX_TOKENS,
                "backend", alive ? activeBackend : models.resolvedRuntimeBackend(),
                "backend_selected", models.runtimeBackend(),
                "started_at", startedAt == null ? "" : startedAt.toString(),
                "log", logFile == null ? "" : root.relativize(logFile).toString(),
                "error", lastError);
        return result;
    }

    public Map<String, Object> slotStats() {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + INFERENCE_PORT + "/slots"))
                    .timeout(Duration.ofMillis(600))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return Json.map("processing", false);
            }
            for (Object raw : Json.asList(Json.parse(response.body()))) {
                Map<String, Object> slot = Json.asObject(raw);
                if (!Json.bool(slot, "is_processing", false)) {
                    continue;
                }
                List<Object> nextTokens = Json.asList(slot.get("next_token"));
                Map<String, Object> next = nextTokens.isEmpty()
                        ? Json.map() : Json.asObject(nextTokens.get(0));
                Map<String, Object> params = Json.asObject(slot.get("params"));
                return Json.map(
                        "processing", true,
                        "n_decoded", Json.integer(next, "n_decoded", 0),
                        "n_remain", Json.integer(next, "n_remain", -1),
                        "max_tokens", Json.integer(params, "max_tokens", 0),
                        "n_ctx", Json.integer(slot, "n_ctx", 0),
                        "n_prompt_tokens", Json.integer(slot, "n_prompt_tokens", 0),
                        "n_prompt_tokens_processed",
                        Json.integer(slot, "n_prompt_tokens_processed", 0),
                        "n_prompt_tokens_cache",
                        Json.integer(slot, "n_prompt_tokens_cache", 0));
            }
        } catch (Exception ignored) {
        }
        return Json.map("processing", false);
    }

    public synchronized Map<String, Object> start(String modelId, int requestedContextSize,
                                                  int requestedParallelSlots) {
        if (process != null && process.isAlive()) {
            return Json.map("ok", false, "error", "Inference service is already running.",
                            "status", status());
        }
        int contextSize = Math.max(AppSettings.MIN_CONTEXT_SIZE,
                Math.min(AppSettings.MAX_CONTEXT_SIZE, requestedContextSize));
        int parallelSlots = Math.max(1,
                Math.min(AppSettings.MAX_PARALLEL_SLOTS, requestedParallelSlots));
        if (probeHealth()) {
            return Json.map("ok", false,
                    "error", "Port " + INFERENCE_PORT + " is already used by an external inference service.",
                    "status", status());
        }
        Path server = models.runtimeServerPath();
        if (server == null) {
            String error = "cuda".equals(models.resolvedRuntimeBackend())
                    ? "The selected CUDA llama.cpp runtime is missing. "
                            + "Install it into runtime/llama.cpp-cuda or switch the backend to CPU."
                    : "llama.cpp runtime is not installed. Install it from the Models tab, "
                            + "or set llm.llama_server in config.json to an existing llama-server binary.";
            return Json.map("ok", false, "error", error);
        }
        String backend = models.resolvedRuntimeBackend();
        String id = modelId == null ? "" : modelId.trim();
        if (id.isEmpty()) {
            id = pickInstalledModel();
        }
        if (id.isEmpty()) {
            return Json.map("ok", false,
                    "error", "No GGUF model is installed. Download a model first.");
        }
        Path model = models.installedModelPath(id);
        if (model == null || !Files.isRegularFile(model)) {
            return Json.map("ok", false, "error", "Model file does not exist: " + id);
        }

        try {
            Path logDir = root.resolve("logs");
            Files.createDirectories(logDir);
            logFile = logDir.resolve("llama-server.log");
            List<String> command = new ArrayList<>();
            command.add(server.toString());
            command.add("-m");
            command.add(model.toString());
            command.add("--host");
            command.add("127.0.0.1");
            command.add("--port");
            command.add(Integer.toString(INFERENCE_PORT));
            command.add("-c");
            command.add(Integer.toString(contextSize));
            command.add("--parallel");
            command.add(Integer.toString(parallelSlots));
            command.add("-t");
            command.add(Integer.toString(Math.max(2, Runtime.getRuntime().availableProcessors() / 2)));
            command.add("--no-webui");
            if ("cuda".equals(backend)) {
                command.add("-ngl");
                command.add("99");
            }
            command.add("--reasoning");
            command.add("auto");
            command.add("--reasoning-format");
            command.add("deepseek");
            command.add("--reasoning-budget");
            command.add("768");

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.environment().put("LLAMA_LOG_COLORS", "0");
            builder.environment().put("LLAMA_ARG_CHAT_TEMPLATE_KWARGS",
                    "{\"enable_thinking\":false}");
            process = builder.start();
            writePid(process.pid(), server);
            activeModelId = id;
            activeModelPath = model.toString();
            activeContextSize = contextSize;
            activeParallelSlots = parallelSlots;
            activeBackend = backend;
            startedAt = Instant.now();
            lastError = "";

            boolean ready = waitForHealth(180);
            if (!ready) {
                String tail = tail(logFile, 40);
                stopLocked();
                lastError = "Inference service did not become ready within 180 seconds. Check the log.";
                return Json.map("ok", false, "error", lastError,
                                "log", "logs/llama-server.log",
                                "tail", tail);
            }
            return Json.map("ok", true,
                    "message", "Inference service is ready.",
                    "model_id", id,
                    "status", status());
        } catch (IOException ex) {
            stopLocked();
            lastError = "Failed to start the inference service: " + ex.getMessage();
            return Json.map("ok", false, "error", lastError);
        }
    }

    public synchronized Map<String, Object> stop() {
        if (process == null || !process.isAlive()) {
            boolean external = probeHealth();
            process = null;
            clearPid();
            return Json.map("ok", true,
                    "message", external
                            ? "The external inference service is not managed by CodeForge and cannot be stopped."
                            : "Inference service is not running.",
                    "external", external);
        }
        stopLocked();
        return Json.map("ok", true, "message", "Inference service stopped.");
    }

    /**
     * A hard crash of the console can leave llama-server running with a huge
     * model still mapped. Stop it on the next launch so the port is usable and
     * the memory is returned, but only when the recorded PID still belongs to
     * our own server under this root.
     */
    public synchronized Map<String, Object> cleanupStaleProcess() {
        if (!Files.isRegularFile(pidFile)) {
            return Json.map("ok", true, "cleaned", false);
        }
        long pid = 0;
        long owner = 0;
        String recordedServer = "";
        try {
            List<String> lines = Files.readAllLines(pidFile, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                pid = Long.parseLong(lines.get(0).trim());
            }
            if (lines.size() > 1) {
                owner = Long.parseLong(lines.get(1).trim());
            }
            if (lines.size() > 2) {
                recordedServer = lines.get(2).trim();
            }
        } catch (Exception ignored) {
        }
        // Another live console owns this runtime; leave its inference server alone.
        if (owner > 0 && ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false)) {
            return Json.map("ok", true, "cleaned", false, "pid", pid,
                    "reason", "another CodeForge server owns the runtime");
        }
        boolean cleaned = false;
        if (pid > 0) {
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null && handle.isAlive()) {
                ProcessHandle.Info info = handle.info();
                String commandLine = info.commandLine().orElse("");
                String image = info.command().orElse("");
                if (commandLine.isBlank() && image.isBlank()) {
                    // Keep the record: a future launch may be allowed to inspect it.
                    return Json.map("ok", true, "cleaned", false, "pid", pid,
                            "reason", "runtime process could not be inspected");
                }
                if (looksLikeOurServer(commandLine, image, recordedServer)) {
                    handle.descendants().forEach(ProcessHandle::destroyForcibly);
                    handle.destroyForcibly();
                    try {
                        handle.onExit().get(8, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                    cleaned = true;
                }
            }
        }
        clearPid();
        return Json.map("ok", true, "cleaned", cleaned, "pid", pid);
    }

    private boolean looksLikeOurServer(String commandLine, String image,
                                       String recordedServer) {
        boolean fullCommandLine = commandLine != null && !commandLine.isBlank();
        String text = fullCommandLine ? commandLine : image;
        if (text == null) {
            return false;
        }
        String command = normalizePath(text);
        if (command.isEmpty() || !command.contains("llama-server")) {
            return false;
        }
        String rootText = normalizePath(root.toAbsolutePath().normalize().toString());
        if (fullCommandLine && (!command.contains("--port")
                || !command.contains(Integer.toString(INFERENCE_PORT)))) {
            return false;
        }
        // Windows can hide the full command line from ProcessHandle; the image
        // path plus the path recorded at launch still identify our runtime.
        if (!recordedServer.isBlank()) {
            String recorded = normalizePath(recordedServer);
            if (fullCommandLine ? command.contains(recorded) : command.equals(recorded)) {
                return true;
            }
        }
        Path expected = models.runtimeServerPath();
        if (expected != null) {
            String expectedText = normalizePath(
                    expected.toAbsolutePath().normalize().toString());
            if (fullCommandLine ? command.contains(expectedText) : command.equals(expectedText)) {
                return true;
            }
        }
        return command.contains(rootText);
    }

    private static String normalizePath(String value) {
        return value.toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private void writePid(long pid, Path server) {
        try {
            Files.createDirectories(pidFile.getParent());
            Files.writeString(pidFile,
                    pid + System.lineSeparator() + ProcessHandle.current().pid()
                            + System.lineSeparator()
                            + server.toAbsolutePath().normalize() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private void clearPid() {
        try {
            Files.deleteIfExists(pidFile);
        } catch (IOException ignored) {
        }
    }

    public void shutdown() {
        synchronized (this) {
            stopLocked();
        }
    }

    private void stopLocked() {
        Process target = process;
        process = null;
        activeModelId = "";
        activeModelPath = "";
        activeContextSize = 0;
        activeParallelSlots = 0;
        startedAt = null;
        clearPid();
        if (target == null) {
            return;
        }
        try {
            target.descendants().forEach(ProcessHandle::destroyForcibly);
            target.destroyForcibly();
            target.waitFor(8, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String pickInstalledModel() {
        List<Object> entries = new ArrayList<>(ModelManager.registry());
        String first = "";
        for (Object object : entries) {
            Map<String, Object> entry = Json.asObject(object);
            String id = Json.str(entry, "id", "");
            if (!models.isModelInstalled(id)) {
                continue;
            }
            if (first.isEmpty()) {
                first = id;
            }
            if (Json.bool(entry, "recommended", false)) {
                return id;
            }
        }
        return first;
    }

    private boolean waitForHealth(int seconds) {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                return false;
            }
            if (probeHealth()) {
                return true;
            }
            try {
                Thread.sleep(750);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean probeHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + INFERENCE_PORT + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return false;
            }
            String body = response.body();
            return body == null || body.isEmpty() || body.contains("ok");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String tail(Path file, int lines) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            StringBuilder builder = new StringBuilder();
            for (int i = from; i < all.size(); ++i) {
                builder.append(all.get(i)).append('\n');
            }
            return builder.toString();
        } catch (IOException ex) {
            return "";
        }
    }

    public synchronized Map<String, Object> logs(int maxLines) {
        Path file = root.resolve("logs").resolve("llama-server.log");
        if (!Files.isRegularFile(file)) {
            return Json.map("ok", false, "error", "No inference server log yet.");
        }
        String text = tail(file, Math.max(10, Math.min(400, maxLines)));
        return Json.map("ok", true, "path", "logs/llama-server.log", "text", text);
    }
}
