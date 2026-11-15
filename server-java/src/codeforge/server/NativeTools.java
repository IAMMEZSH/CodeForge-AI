package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class NativeTools {
    private static final String EXE = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win") ? ".exe" : "";

    public static final class HashResult {
        public boolean ok;
        public boolean match;
        public String sha256 = "";
        public String error = "";
        public String engine = "java";
    }

    private final Path root;
    private final Path tool;
    private volatile Map<String, Object> cachedSysinfo;
    private volatile long sysinfoTime = 0L;

    public NativeTools(Path root) {
        this.root = root;
        this.tool = root.resolve("native").resolve("bin").resolve("cforge_native" + EXE);
    }

    public boolean available() {
        return Files.isRegularFile(tool);
    }

    public Path toolPath() {
        return tool;
    }

    public Map<String, Object> sysinfo() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = cachedSysinfo;
        if (cached != null && now - sysinfoTime < 30_000L) {
            return cached;
        }
        if (!available()) {
            return Json.map("ok", false, "error", "native-c tool not built");
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(tool.toString());
            command.add("sysinfo");
            command.add(root.toString());
            String output = run(command, 15);
            Map<String, Object> parsed = Json.asObject(Json.parse(output));
            if (parsed.isEmpty()) {
                return Json.map("ok", false, "error", "invalid sysinfo output");
            }
            cachedSysinfo = parsed;
            sysinfoTime = now;
            return parsed;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", ex.getMessage());
        }
    }

    public String hashFile(Path file) throws IOException {
        if (available()) {
            try {
                String output = run(List.of(tool.toString(), "hash", file.toString()), 120);
                Map<String, Object> parsed = Json.asObject(Json.parse(output));
                String sha = Json.str(parsed, "sha256", "");
                if (Json.bool(parsed, "ok", false) && !sha.isEmpty()) {
                    return sha;
                }
            } catch (Exception ignored) {
                // Fall through to the JDK implementation.
            }
        }
        return javaHash(file);
    }

    public HashResult verify(Path file, String expectedSha) {
        HashResult result = new HashResult();
        if (available()) {
            try {
                List<String> command = new ArrayList<>();
                command.add(tool.toString());
                command.add("verify");
                command.add(file.toString());
                command.add(expectedSha == null || expectedSha.isEmpty()
                        ? "0000000000000000000000000000000000000000000000000000000000000000"
                        : expectedSha);
                Process process = new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    result.error = "native hash timeout";
                    return result;
                }
                String output = new String(process.getInputStream().readAllBytes(),
                                           StandardCharsets.UTF_8).trim();
                Map<String, Object> parsed = Json.asObject(Json.parse(output));
                if (Json.bool(parsed, "ok", false)) {
                    result.ok = true;
                    result.engine = "c";
                    result.sha256 = Json.str(parsed, "sha256", "");
                    result.match = expectedSha == null || expectedSha.isEmpty()
                            || Json.bool(parsed, "match", false);
                    return result;
                }
            } catch (Exception ignored) {
                // Fall through to the JDK implementation.
            }
        }
        try {
            result.sha256 = javaHash(file);
            result.ok = true;
            result.engine = "java";
            result.match = expectedSha == null || expectedSha.isEmpty()
                    || result.sha256.equalsIgnoreCase(expectedSha);
            return result;
        } catch (Exception ex) {
            result.error = ex.getMessage();
            return result;
        }
    }

    private String javaHash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception ex) {
            throw new IOException("hash failed: " + ex.getMessage(), ex);
        }
    }

    private String run(List<String> command, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("process timeout");
        }
        String stdout = new String(process.getInputStream().readAllBytes(),
                                   StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(),
                                   StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 && stdout.isEmpty()) {
            throw new IOException("exit " + process.exitValue() + ": " + stderr);
        }
        return stdout;
    }
}
