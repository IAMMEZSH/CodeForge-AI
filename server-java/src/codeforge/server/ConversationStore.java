package codeforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only archive of every chat turn. Unlike the searchable memory index,
 * turns are never pruned or truncated: they stay until the user deletes the
 * conversation. Used to keep one task continuous across page reloads and
 * server restarts.
 */
public final class ConversationStore {
    public static final String DEFAULT_CONVERSATION = "main";

    private static final class Turn {
        String ts = "";
        String conversation = "";
        String user = "";
        String assistant = "";
        String model = "";
        String route = "";
        String buildLanguage = "";
        String buildName = "";
        String buildFolder = "";
        String buildArtifact = "";
    }

    private final Path dir;
    private final Path file;

    public ConversationStore(Path root) {
        this.dir = root.resolve("memory");
        this.file = dir.resolve("conversations.jsonl");
    }

    public static String normalizeId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 80) {
            return DEFAULT_CONVERSATION;
        }
        return value.replaceAll("[\\r\\n\\t]", "");
    }

    public synchronized void append(String conversation, String user, String assistant,
                                    String model, String route) {
        append(conversation, user, assistant, model, route, null);
    }

    public synchronized void append(String conversation, String user, String assistant,
                                    String model, String route, Map<String, Object> build) {
        String id = normalizeId(conversation);
        if ((user == null || user.isBlank()) && (assistant == null || assistant.isBlank())) {
            return;
        }
        Turn turn = new Turn();
        turn.ts = Instant.now().toString();
        turn.conversation = id;
        turn.user = user == null ? "" : user;
        turn.assistant = assistant == null ? "" : assistant;
        turn.model = model == null ? "" : model;
        turn.route = route == null ? "" : route;
        if (build != null) {
            turn.buildLanguage = Json.str(build, "language", "");
            turn.buildName = Json.str(build, "name", "");
            turn.buildFolder = Json.str(build, "folder", "");
            turn.buildArtifact = Json.str(build, "artifact", "");
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(file, Json.write(toMap(turn)) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** Last successfully built project of a conversation, if any. */
    public synchronized Map<String, Object> lastBuild(String conversation) {
        List<Turn> all = read();
        String id = normalizeId(conversation);
        for (int i = all.size() - 1; i >= 0; --i) {
            Turn turn = all.get(i);
            if (turn.conversation.equals(id) && !turn.buildFolder.isBlank()) {
                return Json.map(
                        "language", turn.buildLanguage,
                        "name", turn.buildName,
                        "folder", turn.buildFolder,
                        "artifact", turn.buildArtifact);
            }
        }
        return null;
    }

    /** Prior turns injected when the client starts from an empty view. */
    public synchronized List<Object> messages(String conversation, int turns) {
        List<Turn> all = read();
        String id = normalizeId(conversation);
        int want = Math.max(2, Math.min(64, turns));
        List<Turn> picked = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && picked.size() < want; --i) {
            if (all.get(i).conversation.equals(id)) {
                picked.add(0, all.get(i));
            }
        }
        List<Object> out = new ArrayList<>();
        for (Turn turn : picked) {
            if (!turn.user.isBlank()) {
                out.add(Json.map("role", "user", "content", clip(turn.user, 1600)));
            }
            if (!turn.assistant.isBlank()) {
                out.add(Json.map("role", "assistant", "content", clip(turn.assistant, 4000)));
            }
        }
        return out;
    }

    /** Full history for restoring the console after a reload. */
    public synchronized List<Map<String, Object>> history(String conversation, int limit) {
        List<Turn> all = read();
        String id = normalizeId(conversation);
        int want = Math.max(2, Math.min(500, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Turn turn : all) {
            if (!turn.conversation.equals(id)) {
                continue;
            }
            out.add(Json.map("role", "user", "content", turn.user,
                    "ts", turn.ts, "model", turn.model));
            if (!turn.assistant.isBlank()) {
                out.add(Json.map("role", "assistant", "content", turn.assistant,
                        "ts", turn.ts, "model", turn.model));
            }
        }
        return out.size() <= want ? out
                : new ArrayList<>(out.subList(out.size() - want, out.size()));
    }

    public synchronized List<Object> list() {
        List<Turn> all = read();
        Map<String, Map<String, Object>> sessions = new LinkedHashMap<>();
        for (Turn turn : all) {
            Map<String, Object> session = sessions.computeIfAbsent(turn.conversation,
                    key -> Json.map("id", key, "turns", 0, "last_ts", "", "preview", ""));
            session.put("turns", Json.integer(session, "turns", 0) + 1);
            session.put("last_ts", turn.ts);
            String preview = turn.user.isBlank() ? turn.assistant : turn.user;
            session.put("preview", clip(preview, 160));
        }
        return new ArrayList<>(sessions.values());
    }

    public synchronized Map<String, Object> delete(String conversation) {
        String id = normalizeId(conversation);
        List<Turn> all = read();
        List<Turn> kept = new ArrayList<>();
        int removed = 0;
        for (Turn turn : all) {
            if (turn.conversation.equals(id)) {
                ++removed;
            } else {
                kept.add(turn);
            }
        }
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve("conversations.jsonl.tmp");
            StringBuilder out = new StringBuilder();
            for (Turn turn : kept) {
                out.append(Json.write(toMap(turn))).append('\n');
            }
            Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            return Json.map("ok", false, "error", "Cannot delete conversation: "
                    + ex.getMessage());
        }
        return Json.map("ok", true, "conversation", id, "removed", removed,
                "remaining", kept.size());
    }

    public synchronized Map<String, Object> stats() {
        List<Turn> all = read();
        java.util.Set<String> ids = new java.util.HashSet<>();
        long bytes = 0;
        try {
            if (Files.isRegularFile(file)) {
                bytes = Files.size(file);
            }
        } catch (IOException ignored) {
        }
        for (Turn turn : all) {
            ids.add(turn.conversation);
        }
        return Json.map("ok", true, "conversations", ids.size(), "turns", all.size(),
                "bytes", bytes);
    }

    private List<Turn> read() {
        List<Turn> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> map = Json.asObject(Json.parse(line));
                    Turn turn = new Turn();
                    turn.ts = Json.str(map, "ts", "");
                    turn.conversation = normalizeId(Json.str(map, "conversation", ""));
                    turn.user = Json.str(map, "user", "");
                    turn.assistant = Json.str(map, "assistant", "");
                    turn.model = Json.str(map, "model", "");
                    turn.route = Json.str(map, "route", "");
                    Map<String, Object> build = Json.asObject(map.get("build"));
                    turn.buildLanguage = Json.str(build, "language", "");
                    turn.buildName = Json.str(build, "name", "");
                    turn.buildFolder = Json.str(build, "folder", "");
                    turn.buildArtifact = Json.str(build, "artifact", "");
                    if (!turn.user.isBlank() || !turn.assistant.isBlank()) {
                        out.add(turn);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Map<String, Object> toMap(Turn turn) {
        Map<String, Object> map = Json.map(
                "ts", turn.ts,
                "conversation", turn.conversation,
                "user", turn.user,
                "assistant", turn.assistant,
                "model", turn.model,
                "route", turn.route);
        if (!turn.buildFolder.isBlank()) {
            map.put("build", Json.map(
                    "language", turn.buildLanguage,
                    "name", turn.buildName,
                    "folder", turn.buildFolder,
                    "artifact", turn.buildArtifact));
        }
        return map;
    }

    private static String clip(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
