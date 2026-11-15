package codeforge.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CoreBridge {
    private static final String EXE = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win") ? ".exe" : "";

    private final Path root;
    private final Path coreExe;
    private final Set<Process> active = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, Process> byRequest = new ConcurrentHashMap<>();
    private final Map<String, Long> cancelRequested = new ConcurrentHashMap<>();

    public CoreBridge(Path root) {
        this.root = root;
        this.coreExe = root.resolve("native").resolve("bin").resolve("CodeForgeCore" + EXE);
    }

    public boolean available() {
        return Files.isRegularFile(coreExe);
    }

    public Path coreExe() {
        return coreExe;
    }

    public Map<String, Object> call(String command,
                                    Map<String, Object> request,
                                    int timeoutSeconds) {
        return call(command, request, timeoutSeconds, "");
    }

    public Map<String, Object> call(String command,
                                    Map<String, Object> request,
                                    int timeoutSeconds,
                                    String requestId) {
        String id = requestId == null ? "" : requestId.trim();
        if (!id.isEmpty() && cancelRequested.remove(id) != null) {
            return cancelledResult();
        }
        if (!available()) {
            return Json.map("ok", false,
                    "error", "C++ main core is missing. Run the build script (python aux-python/bootstrap.py --build) to generate native/bin/CodeForgeCore.");
        }
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        try {
            Path logDir = root.resolve("logs");
            Files.createDirectories(logDir);
            stdout = Files.createTempFile(logDir, "core-", ".out");
            stderr = Files.createTempFile(logDir, "core-", ".err");

            ProcessBuilder builder = new ProcessBuilder(
                    coreExe.toString(), command, "--root", root.toString());
            builder.directory(root.toFile());
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            process = builder.start();
            active.add(process);
            if (!id.isEmpty()) {
                byRequest.put(id, process);
                if (cancelRequested.remove(id) != null) {
                    kill(process);
                }
            }

            String payload = request == null ? "{}" : Json.write(request);
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            boolean cancelled = !id.isEmpty() && cancelRequested.remove(id) != null;
            if (!finished) {
                kill(process);
                process.waitFor(3, TimeUnit.SECONDS);
                return Json.map("ok", false,
                        "error", "C++ main core timed out after " + timeoutSeconds + " seconds");
            }
            if (cancelled) {
                return cancelledResult();
            }

            String outText = Files.readString(stdout, StandardCharsets.UTF_8).trim();
            String errText = Files.readString(stderr, StandardCharsets.UTF_8).trim();
            if (outText.isEmpty()) {
                return Json.map("ok", false,
                        "error", "C++ main core returned no data",
                        "detail", errText);
            }
            try {
                Object parsed = Json.parse(outText);
                Map<String, Object> result = Json.asObject(parsed);
                if (result.isEmpty()) {
                    return Json.map("ok", false,
                            "error", "C++ main core returned non-object JSON",
                            "detail", outText.substring(0, Math.min(500, outText.length())));
                }
                return result;
            } catch (Exception ex) {
                return Json.map("ok", false,
                        "error", "C++ main core returned unparseable JSON: " + ex.getMessage(),
                        "detail", outText.substring(0, Math.min(800, outText.length())),
                        "stderr", errText.substring(0, Math.min(800, errText.length())));
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Json.map("ok", false, "error", "C++ main core call failed: " + ex.getMessage());
        } finally {
            if (process != null) {
                active.remove(process);
                if (!id.isEmpty()) {
                    byRequest.remove(id, process);
                    cancelRequested.remove(id);
                }
            }
            try {
                if (stdout != null) Files.deleteIfExists(stdout);
            } catch (IOException ignored) {
            }
            try {
                if (stderr != null) Files.deleteIfExists(stderr);
            } catch (IOException ignored) {
            }
        }
    }

    public boolean cancel(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        String id = requestId.trim();
        long now = System.currentTimeMillis();
        cancelRequested.entrySet().removeIf(entry -> now - entry.getValue() > 120_000L);
        Process process = byRequest.get(id);
        if (process == null) {
            cancelRequested.put(id, now);
            return false;
        }
        if (!process.isAlive()) {
            return false;
        }
        cancelRequested.put(id, now);
        kill(process);
        return true;
    }

    public void clearCancellation(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            cancelRequested.remove(requestId.trim());
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static Map<String, Object> cancelledResult() {
        return Json.map(
                "ok", false,
                "cancelled", true,
                "error", "Interrupted by user");
    }

    public void shutdown() {
        List<Process> processes;
        synchronized (active) {
            processes = new ArrayList<>(active);
        }
        for (Process process : processes) {
            kill(process);
        }
        active.clear();
        byRequest.clear();
        cancelRequested.clear();
    }
}
