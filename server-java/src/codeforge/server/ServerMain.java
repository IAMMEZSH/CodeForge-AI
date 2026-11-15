package codeforge.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    private static HttpServer server;
    private static RuntimeManager runtime;
    private static CoreBridge core;
    private static ApiHandler handler;
    private static final CountDownLatch STOP_LATCH = new CountDownLatch(1);

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        Path root = resolveRoot(options.get("root"));
        int port = parseInt(options.get("port"), 8765);
        if (port < 1 || port > 65535) {
            System.err.println("[codeforge] invalid port: " + port);
            System.exit(2);
        }

        System.out.println("[codeforge] root   : " + root);
        System.out.println("[codeforge] port   : " + port);
        System.out.println("[codeforge] java   : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("[codeforge] os     : " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch"));

        NativeTools nativeTools = new NativeTools(root);
        core = new CoreBridge(root);
        ModelManager models = new ModelManager(root, nativeTools);
        AppSettings settings = new AppSettings(root);
        runtime = new RuntimeManager(root, models, settings);
        MemoryStore memory = new MemoryStore(settings);
        ProjectIndex projectIndex = new ProjectIndex(settings);
        OnlineService online = new OnlineService(settings, memory);
        ComplianceService compliance = new ComplianceService(settings, online);
        projectIndex.refreshAsync();
        if (!core.available()) {
            System.err.println("[codeforge] warning: C++ core not found at " + core.coreExe());
            System.err.println("[codeforge] warning: chat/debug endpoints will return an error");
        }

        server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        } catch (IOException ex) {
            System.err.println("[codeforge] cannot bind 127.0.0.1:" + port + " -> " + ex.getMessage());
            System.err.println("[codeforge] another CodeForge server may already be running.");
            System.exit(3);
        }

        final HttpServer boundServer = server;
        handler = new ApiHandler(root, core, models, runtime, nativeTools,
                settings, memory, projectIndex, online, compliance, () -> {
            new Thread(() -> {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                }
                stopAll();
                boundServer.stop(1);
                STOP_LATCH.countDown();
            }, "shutdown").start();
        });
        server.createContext("/", handler);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "http-worker");
            thread.setDaemon(true);
            return thread;
        }));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopAll();
        }, "codeforge-shutdown"));

        server.start();
        String base = "http://127.0.0.1:" + port;
        System.out.println("[codeforge] web console : " + base + "/console/");
        System.out.println("[codeforge] api         : " + base + "/api/status");
        System.out.println("CODEFORGE_SERVER_READY " + base);
        System.out.flush();

        try {
            STOP_LATCH.await();
        } catch (InterruptedException ignored) {
        }
        System.out.println("[codeforge] server stopped");
    }

    private static synchronized void stopAll() {
        if (handler != null) {
            handler.close();
            handler = null;
        }
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
        if (core != null) {
            core.shutdown();
            core = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; ++i) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key;
                String value;
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    key = arg.substring(2, eq);
                    value = arg.substring(eq + 1);
                } else {
                    key = arg.substring(2);
                    value = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                            ? args[++i] : "true";
                }
                options.put(key.toLowerCase(), value);
            }
        }
        return options;
    }

    private static Path resolveRoot(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        String property = System.getProperty("codeforge.root");
        if (property != null && !property.isBlank()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        Path probe = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 6 && probe != null; ++i) {
            if (looksLikeRoot(probe)) {
                return probe;
            }
            probe = probe.getParent();
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static boolean looksLikeRoot(Path path) {
        return Files.isRegularFile(path.resolve("config.json"))
                || Files.isDirectory(path.resolve("server-java"))
                || Files.isRegularFile(path.resolve("native").resolve("bin").resolve("CodeForgeCore.exe"))
                || Files.isRegularFile(path.resolve("native").resolve("bin").resolve("CodeForgeCore"));
    }

    private static int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @SuppressWarnings("unused")
    private static void logUtf8(String message) {
        System.out.write((message + "\n").getBytes(StandardCharsets.UTF_8), 0,
                (message + "\n").getBytes(StandardCharsets.UTF_8).length);
        System.out.flush();
    }
}
