package codeforge.server;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Publishes a local project to GitHub or GitCode through their REST APIs.
 * Tokens come from the local vault and are never returned or logged.
 */
public final class Publisher {
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GITHUB_UPLOAD = "https://uploads.github.com";
    private static final String GITCODE_API = "https://api.gitcode.com/api/v5";

    private final Path root;
    private final KeyStore secrets;
    private final HttpClient http;

    public Publisher(Path root, KeyStore secrets) {
        this.root = root.toAbsolutePath().normalize();
        this.secrets = secrets;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("github_token", !githubToken(false).isBlank());
        out.put("gitcode_token", !gitcodeToken(false).isBlank());
        out.put("github_source", tokenSource("github"));
        out.put("gitcode_source", tokenSource("gitcode"));
        Path release = root.resolve("release");
        out.put("release_folder", Files.isDirectory(release) ? release.toString() : "");
        out.put("default_tag", "v" + readVersion());
        out.put("github_default", "IAMMEZSH/CodeForge-AI");
        out.put("gitcode_default", "gcw_So3EkhfR/CodeForge-AI");
        return out;
    }

    /**
     * Vault first; fall back to the already-authenticated GitHub CLI keyring.
     * The token is only used in-process and is never logged or returned.
     */
    private String githubToken() {
        return githubToken(true);
    }

    private String githubToken(boolean allowCredential) {
        String stored = secrets.secretValue("GITHUB_TOKEN");
        if (!stored.isBlank()) {
            return stored;
        }
        for (String name : new String[]{"GITHUB_TOKEN", "GH_TOKEN"}) {
            String value = envToken(name);
            if (!value.isBlank()) {
                return value;
            }
        }
        String gh = commandToken("gh", "auth", "token");
        if (!gh.isBlank()) {
            return gh;
        }
        return allowCredential ? credentialToken("github.com") : "";
    }

    private String gitcodeToken() {
        return gitcodeToken(true);
    }

    private String gitcodeToken(boolean allowCredential) {
        String stored = secrets.secretValue("GITCODE_TOKEN");
        if (!stored.isBlank()) {
            return stored;
        }
        for (String name : new String[]{"GITCODE_TOKEN", "GITCODE_ACCESS_TOKEN"}) {
            String value = envToken(name);
            if (!value.isBlank()) {
                return value;
            }
        }
        return allowCredential ? credentialToken("gitcode.com") : "";
    }

    private String tokenSource(String platform) {
        boolean github = "github".equals(platform);
        String name = github ? "GITHUB_TOKEN" : "GITCODE_TOKEN";
        if (!secrets.secretValue(name).isBlank()) {
            return "vault";
        }
        if (!envToken(github ? "GITHUB_TOKEN" : "GITCODE_TOKEN").isBlank()
                || !envToken(github ? "GH_TOKEN" : "GITCODE_ACCESS_TOKEN").isBlank()) {
            return "environment";
        }
        if (github && !commandToken("gh", "auth", "token").isBlank()) {
            return "github-cli";
        }
        return "missing";
    }

    private static String envToken(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static String commandToken(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            String token = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            return token.length() >= 20 && !token.contains(" ") ? token : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Reads an already-stored HTTPS credential from the local Git helper. */
    private static String credentialToken(String host) {
        try {
            Process process = new ProcessBuilder("git", "credential", "fill").start();
            try (var input = process.getOutputStream()) {
                input.write(("protocol=https\nhost=" + host + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                input.flush();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            String text = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                if (line.startsWith("password=")) {
                    return line.substring("password=".length()).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String readVersion() {
        try {
            Path app = root.resolve("server-java").resolve("src")
                    .resolve("codeforge").resolve("server").resolve("AppSettings.java");
            if (Files.isRegularFile(app)) {
                String text = Files.readString(app, StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("VERSION\\s*=\\s*\"([^\"]+)\"").matcher(text);
                if (m.find()) {
                    return m.group(1);
                }
            }
            Path pkg = root.resolve("VERSION");
            if (Files.isRegularFile(pkg)) {
                return Files.readString(pkg, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
        }
        return "1.0.8";
    }

    public Map<String, Object> publish(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        String platform = Json.str(request, "platform", "github")
                .trim().toLowerCase(Locale.ROOT);
        out.put("platform", platform);
        String token = "github".equals(platform) ? githubToken() : gitcodeToken();
        if (token.isBlank()) {
            out.put("error", ("github".equals(platform)
                    ? "No GitHub token found automatically. Sign in with the GitHub CLI or store GITHUB_TOKEN in Publish → Tokens."
                    : "No GitCode token found. Sign in to gitcode.com, create a personal access token once at "
                    + "https://gitcode.com/setting/token-classic, then paste it in Publish → Tokens; "
                    + "after that CodeForge reuses it automatically."));
            out.put("needs_token", true);
            out.put("steps", steps);
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        }
        String owner = Json.str(request, "owner", "").trim();
        String repo = Json.str(request, "repo", "").trim();
        String combined = owner.contains("/") ? owner : repo;
        if (combined.contains("/")) {
            String[] parts = combined.split("/", 2);
            owner = parts[0].trim();
            repo = parts[1].trim();
        }
        repo = repo.replaceAll("\\.git$", "");
        if (repo.isBlank()) {
            out.put("error", "repository name is required");
            out.put("steps", steps);
            return out;
        }
        String tag = Json.str(request, "tag", "").trim();
        if (tag.isBlank()) {
            tag = "v" + readVersion();
        }
        String name = Json.str(request, "name", tag);
        String body = Json.str(request, "body", "");
        String target = Json.str(request, "target", "");
        boolean createRepo = Json.bool(request, "create_repo", false);
        boolean createRelease = Json.bool(request, "create_release", true);
        boolean overwrite = Json.bool(request, "overwrite", true);
        boolean deleteOlder = Json.bool(request, "delete_older", false);
        boolean draft = Json.bool(request, "draft", false);
        boolean prerelease = Json.bool(request, "prerelease", false);
        boolean isPrivate = Json.bool(request, "private", false);
        try {
            List<Path> assets = collectAssets(request);
            Map<String, Object> result = "github".equals(platform)
                    ? publishGithub(token, owner, repo, tag, name, body, target,
                    assets, createRepo, createRelease, overwrite, deleteOlder,
                    draft, prerelease, isPrivate, steps)
                    : publishGitcode(token, owner, repo, tag, name, body, target,
                    assets, createRepo, createRelease, overwrite, deleteOlder,
                    prerelease, isPrivate, steps);
            out.putAll(result);
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            step(steps, "aborted", false, out.get("error").toString());
        }
        out.put("steps", steps);
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    private Map<String, Object> publishGithub(String token, String owner, String repo,
                                              String tag, String name, String body,
                                              String target, List<Path> assets,
                                              boolean createRepo, boolean createRelease,
                                              boolean overwrite, boolean deleteOlder,
                                              boolean draft, boolean prerelease,
                                              boolean isPrivate,
                                              List<Map<String, Object>> steps)
            throws IOException, InterruptedException {
        Map<String, Object> out = new LinkedHashMap<>();
        String api = System.getProperty("codeforge.github.api", GITHUB_API);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "CodeForge-AI-Publisher");

        if (owner.isBlank()) {
            Map<String, Object> me = json(send("GET", api + "/user", headers, null,
                    Duration.ofSeconds(30)));
            owner = Json.str(me, "login", "");
            if (owner.isBlank()) {
                throw new IOException("could not determine GitHub account");
            }
            step(steps, "account", true, "authenticated as " + owner);
        } else {
            step(steps, "account", true, "publishing to " + owner + "/" + repo);
        }

        HttpResponse<String> repoResponse = send("GET",
                api + "/repos/" + owner + "/" + repo, headers, null,
                Duration.ofSeconds(30));
        String defaultBranch = "main";
        if (repoResponse.statusCode() == 404) {
            if (!createRepo) {
                throw new IOException("GitHub repository " + owner + "/" + repo
                        + " was not found. Enable create repository or create it first.");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", repo);
            payload.put("private", isPrivate);
            payload.put("description", body.isBlank()
                    ? "Published with CodeForge AI" : body.substring(0, Math.min(200, body.length())));
            payload.put("auto_init", true);
            payload.put("has_issues", true);
            HttpResponse<String> created = sendJson("POST", api + "/user/repos", headers,
                    payload, Duration.ofSeconds(60));
            if (created.statusCode() >= 300) {
                throw new IOException("GitHub repository creation failed: "
                        + apiError(created));
            }
            step(steps, "repository", true, "created " + owner + "/" + repo);
            TimeUnit.SECONDS.sleep(1);
        } else if (repoResponse.statusCode() >= 300) {
            throw new IOException("GitHub repository check failed: " + apiError(repoResponse));
        } else {
            Map<String, Object> info = parse(repoResponse.body());
            String branch = Json.str(info, "default_branch", "");
            if (!branch.isBlank()) {
                defaultBranch = branch;
            }
            step(steps, "repository", true, "found " + owner + "/" + repo);
        }
        if (target.isBlank()) {
            target = defaultBranch;
        }

        long releaseId = 0;
        String releaseUrl = "";
        Map<String, Object> release = null;
        HttpResponse<String> existing = send("GET",
                api + "/repos/" + owner + "/" + repo + "/releases/tags/"
                        + encodePath(tag), headers, null, Duration.ofSeconds(30));
        if (existing.statusCode() == 200) {
            release = parse(existing.body());
            releaseId = Json.lon(release, "id", 0);
            releaseUrl = Json.str(release, "html_url", "");
            step(steps, "release", true, "release " + tag + " already exists");
        } else if (!createRelease) {
            throw new IOException("Release " + tag + " was not found and release creation "
                    + "is disabled");
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tag_name", tag);
            payload.put("target_commitish", target);
            payload.put("name", name.isBlank() ? tag : name);
            payload.put("body", body);
            payload.put("draft", draft);
            payload.put("prerelease", prerelease);
            HttpResponse<String> created = sendJson("POST",
                    api + "/repos/" + owner + "/" + repo + "/releases", headers, payload,
                    Duration.ofSeconds(60));
            if (created.statusCode() >= 300) {
                throw new IOException("GitHub release creation failed: "
                        + apiError(created));
            }
            release = parse(created.body());
            releaseId = Json.lon(release, "id", 0);
            releaseUrl = Json.str(release, "html_url", "");
            step(steps, "release", true, "created release " + tag);
        }

        Map<String, Long> existingAssets = new LinkedHashMap<>();
        if (release != null) {
            for (Object raw : Json.asList(release.get("assets"))) {
                Map<String, Object> asset = Json.asObject(raw);
                existingAssets.put(Json.str(asset, "name", ""),
                        Json.lon(asset, "id", 0));
            }
        }
        int uploaded = 0;
        int skipped = 0;
        for (Path file : assets) {
            String fileName = file.getFileName().toString();
            long size = Files.size(file);
            if (existingAssets.containsKey(fileName)) {
                if (!overwrite) {
                    ++skipped;
                    step(steps, "asset " + fileName, true,
                            "already present · skipped");
                    continue;
                }
                long oldId = existingAssets.get(fileName);
                if (oldId > 0) {
                    send("DELETE", api + "/repos/" + owner + "/" + repo + "/releases/assets/"
                            + oldId, headers, null, Duration.ofSeconds(30));
                }
            }
            HttpResponse<String> up = uploadGithub(file, api, headers, owner, repo,
                    releaseId, fileName);
            if (up.statusCode() >= 300) {
                throw new IOException("Upload failed for " + fileName + ": "
                        + apiError(up));
            }
            ++uploaded;
            step(steps, "asset " + fileName, true, human(size) + " uploaded");
        }

        if (deleteOlder) {
            deleteOlderGithub(api, headers, owner, repo, tag, steps);
        }
        out.put("ok", true);
        out.put("owner", owner);
        out.put("repo", repo);
        out.put("tag", tag);
        out.put("release_url", releaseUrl.isBlank()
                ? "https://github.com/" + owner + "/" + repo + "/releases/tag/" + tag
                : releaseUrl);
        out.put("assets_uploaded", uploaded);
        out.put("assets_skipped", skipped);
        out.put("assets_total", assets.size());
        return out;
    }

    private HttpResponse<String> uploadGithub(Path file, String api,
                                              Map<String, String> headers,
                                              String owner, String repo, long releaseId,
                                              String fileName)
            throws IOException, InterruptedException {
        Map<String, String> uploadHeaders = new LinkedHashMap<>(headers);
        uploadHeaders.put("Content-Type", "application/octet-stream");
        uploadHeaders.remove("Content-Length");
        String url = GITHUB_UPLOAD + "/repos/" + owner + "/" + repo + "/releases/"
                + releaseId + "/assets?name=" + encode(fileName);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(20))
                .POST(HttpRequest.BodyPublishers.ofFile(file));
        uploadHeaders.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void deleteOlderGithub(String api, Map<String, String> headers,
                                   String owner, String repo, String keepTag,
                                   List<Map<String, Object>> steps)
            throws IOException, InterruptedException {
        HttpResponse<String> list = send("GET", api + "/repos/" + owner + "/" + repo
                + "/releases?per_page=100", headers, null, Duration.ofSeconds(60));
        if (list.statusCode() >= 300) {
            step(steps, "cleanup", false, "could not list releases: " + apiError(list));
            return;
        }
        int removed = 0;
        for (Object raw : Json.asList(Json.parse(list.body()))) {
            Map<String, Object> release = Json.asObject(raw);
            String tag = Json.str(release, "tag_name", "");
            long id = Json.lon(release, "id", 0);
            if (tag.equals(keepTag) || id <= 0) {
                continue;
            }
            HttpResponse<String> deleted = send("DELETE", api + "/repos/" + owner + "/"
                    + repo + "/releases/" + id, headers, null, Duration.ofSeconds(30));
            if (deleted.statusCode() < 300) {
                ++removed;
                step(steps, "cleanup " + tag, true, "old release deleted");
            } else {
                step(steps, "cleanup " + tag, false, apiError(deleted));
            }
        }
        if (removed == 0) {
            step(steps, "cleanup", true, "no older releases to delete");
        }
    }

    private Map<String, Object> publishGitcode(String token, String owner, String repo,
                                               String tag, String name, String body,
                                               String target, List<Path> assets,
                                               boolean createRepo, boolean createRelease,
                                               boolean overwrite, boolean deleteOlder,
                                               boolean prerelease, boolean isPrivate,
                                               List<Map<String, Object>> steps)
            throws IOException, InterruptedException {
        Map<String, Object> out = new LinkedHashMap<>();
        String api = GITCODE_API;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "CodeForge-AI-Publisher");

        if (owner.isBlank()) {
            HttpResponse<String> me = send("GET", api + "/user" + tokenQuery(token),
                    headers, null, Duration.ofSeconds(30));
            if (me.statusCode() < 300) {
                Map<String, Object> info = parse(me.body());
                owner = Json.str(info, "login", Json.str(info, "username", ""));
            }
            if (owner.isBlank()) {
                throw new IOException("could not determine GitCode account");
            }
            step(steps, "account", true, "authenticated as " + owner);
        } else {
            step(steps, "account", true, "publishing to " + owner + "/" + repo);
        }

        HttpResponse<String> repoResponse = send("GET", api + "/repos/" + owner + "/"
                + repo + tokenQuery(token), headers, null, Duration.ofSeconds(30));
        if (repoResponse.statusCode() == 404) {
            if (!createRepo) {
                throw new IOException("GitCode repository " + owner + "/" + repo
                        + " was not found. Enable create repository or create it first.");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", repo);
            payload.put("path", repo);
            payload.put("private", isPrivate);
            payload.put("description", body.isBlank()
                    ? "Published with CodeForge AI"
                    : body.substring(0, Math.min(200, body.length())));
            payload.put("auto_init", true);
            payload.put("init_readme", true);
            HttpResponse<String> created = sendJson("POST",
                    api + "/user/repos" + tokenQuery(token), headers, payload,
                    Duration.ofSeconds(60));
            if (created.statusCode() >= 300) {
                throw new IOException("GitCode repository creation failed: "
                        + apiError(created));
            }
            step(steps, "repository", true, "created " + owner + "/" + repo);
            TimeUnit.SECONDS.sleep(1);
        } else if (repoResponse.statusCode() >= 300) {
            throw new IOException("GitCode repository check failed: "
                    + apiError(repoResponse));
        } else {
            step(steps, "repository", true, "found " + owner + "/" + repo);
        }

        Map<String, Object> release = null;
        String releaseUrl = "";
        HttpResponse<String> existing = send("GET", api + "/repos/" + owner + "/" + repo
                + "/releases/tags/" + encodePath(tag) + tokenQuery(token), headers, null,
                Duration.ofSeconds(30));
        if (existing.statusCode() == 200) {
            release = parse(existing.body());
            releaseUrl = Json.str(release, "html_url", "");
            step(steps, "release", true, "release " + tag + " already exists");
        } else if (!createRelease) {
            throw new IOException("Release " + tag + " was not found and release creation "
                    + "is disabled");
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tag_name", tag);
            payload.put("name", name.isBlank() ? tag : name);
            payload.put("body", body);
            if (!target.isBlank()) {
                payload.put("target_commitish", target);
            }
            payload.put("release_status", prerelease ? "pre" : "latest");
            HttpResponse<String> created = sendJson("POST", api + "/repos/" + owner + "/"
                    + repo + "/releases" + tokenQuery(token), headers, payload,
                    Duration.ofSeconds(60));
            if (created.statusCode() >= 300) {
                throw new IOException("GitCode release creation failed: "
                        + apiError(created));
            }
            release = parse(created.body());
            releaseUrl = Json.str(release, "html_url", "");
            step(steps, "release", true, "created release " + tag);
        }

        java.util.Set<String> existingAssets = new java.util.LinkedHashSet<>();
        if (release != null) {
            for (Object raw : Json.asList(release.get("assets"))) {
                Map<String, Object> asset = Json.asObject(raw);
                String assetName = Json.str(asset, "name", "");
                if (!assetName.isBlank()) {
                    existingAssets.add(assetName);
                }
            }
        }
        int uploaded = 0;
        int skipped = 0;
        for (Path file : assets) {
            String fileName = file.getFileName().toString();
            long size = Files.size(file);
            if (existingAssets.contains(fileName) && !overwrite) {
                ++skipped;
                step(steps, "asset " + fileName, true, "already present · skipped");
                continue;
            }
            String uploadUrl = api + "/repos/" + owner + "/" + repo + "/releases/"
                    + encodePath(tag) + "/upload_url" + tokenQuery(token)
                    + "&file_name=" + encode(fileName);
            HttpResponse<String> info = send("GET", uploadUrl, headers, null,
                    Duration.ofSeconds(60));
            if (info.statusCode() >= 300) {
                throw new IOException("Could not get upload URL for " + fileName + ": "
                        + apiError(info));
            }
            Map<String, Object> signed = parse(info.body());
            String url = Json.str(signed, "url", "");
            if (url.isBlank()) {
                throw new IOException("GitCode upload URL response did not contain url");
            }
            Map<String, Object> signedHeaders = Json.asObject(signed.get("headers"));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(20))
                    .PUT(HttpRequest.BodyPublishers.ofFile(file));
            for (Map.Entry<String, Object> entry : signedHeaders.entrySet()) {
                String key = entry.getKey();
                if (key == null || entry.getValue() == null
                        || key.equalsIgnoreCase("Host")
                        || key.equalsIgnoreCase("Content-Length")
                        || key.equalsIgnoreCase("Connection")) {
                    continue;
                }
                builder.header(key, String.valueOf(entry.getValue()));
            }
            HttpResponse<String> put = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (put.statusCode() >= 300) {
                throw new IOException("Upload failed for " + fileName + " (HTTP "
                        + put.statusCode() + "): " + cap(put.body(), 400));
            }
            ++uploaded;
            step(steps, "asset " + fileName, true, human(size) + " uploaded");
        }

        if (deleteOlder) {
            step(steps, "cleanup", true,
                    "GitCode cleanup is left to the platform UI; existing releases were kept");
        }
        out.put("ok", true);
        out.put("owner", owner);
        out.put("repo", repo);
        out.put("tag", tag);
        out.put("release_url", releaseUrl.isBlank()
                ? "https://gitcode.com/" + owner + "/" + repo + "/releases/tag/" + tag
                : releaseUrl);
        out.put("assets_uploaded", uploaded);
        out.put("assets_skipped", skipped);
        out.put("assets_total", assets.size());
        return out;
    }

    private List<Path> collectAssets(Map<String, Object> request) throws IOException {
        List<Path> files = new ArrayList<>();
        List<Object> listed = Json.asList(request.get("assets"));
        if (!listed.isEmpty()) {
            for (Object raw : listed) {
                Path path = Path.of(String.valueOf(raw));
                if (!path.isAbsolute()) {
                    path = root.resolve(path);
                }
                path = path.toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) {
                    files.add(path);
                } else {
                    throw new IOException("asset not found: " + path);
                }
            }
            return files;
        }
        String folder = Json.str(request, "assets_folder", "");
        if (folder.isBlank()) {
            folder = root.resolve("release").toString();
        }
        Path dir = Path.of(folder);
        if (!dir.isAbsolute()) {
            dir = root.resolve(dir);
        }
        dir = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted((a, b) -> a.getFileName().toString()
                            .compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    private HttpResponse<String> send(String method, String url,
                                      Map<String, String> headers, Object body,
                                      Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout);
        headers.forEach(builder::header);
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(Json.write(body),
                    StandardCharsets.UTF_8);
            builder.method(method, publisher);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String method, String url,
                                          Map<String, String> headers, Object body,
                                          Duration timeout)
            throws IOException, InterruptedException {
        Map<String, String> withType = new LinkedHashMap<>(headers);
        withType.put("Content-Type", "application/json");
        return send(method, url, withType, body, timeout);
    }

    private static Map<String, Object> json(HttpResponse<String> response)
            throws IOException {
        if (response.statusCode() >= 300) {
            throw new IOException("GitHub API error " + response.statusCode() + ": "
                    + apiError(response));
        }
        return parse(response.body());
    }

    private static Map<String, Object> parse(String body) {
        try {
            return Json.asObject(Json.parse(body));
        } catch (RuntimeException ex) {
            return new LinkedHashMap<>();
        }
    }

    private static String apiError(HttpResponse<String> response) {
        String body = cap(response.body(), 600);
        Map<String, Object> parsed = parse(response.body());
        String message = Json.str(parsed, "message", "");
        return message.isBlank() ? body : message + " (" + body + ")";
    }

    private static String tokenQuery(String token) {
        return "?access_token=" + encode(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void step(List<Map<String, Object>> steps, String stage,
                             boolean ok, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("at", System.currentTimeMillis());
        item.put("stage", stage);
        item.put("ok", ok);
        item.put("message", message);
        steps.add(item);
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String cap(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}