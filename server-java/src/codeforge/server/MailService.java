package codeforge.server;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * SMTP mail for the console and the agent. Settings live in config.json;
 * the password lives in the local vault under a named secret and is never
 * returned by the API. Supports implicit TLS (465), STARTTLS (587) and
 * plain SMTP (25, local test servers), HTML bodies and attachments.
 */
public final class MailService {
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;
    private static final String DEFAULT_SECRET = "smtp_password";

    private final Path root;
    private final Path configFile;
    private final KeyStore keys;

    public MailService(Path root, KeyStore keys) {
        this.root = root.toAbsolutePath().normalize();
        this.configFile = root.resolve("config.json");
        this.keys = keys;
    }

    public Map<String, Object> configView() {
        try {
            Map<String, Object> doc = readDoc();
            Map<String, Object> mail = Json.asObject(doc.get("mail"));
            String secret = Json.str(mail, "secret_name", DEFAULT_SECRET);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("config", configFile.toString());
            out.put("host", Json.str(mail, "host", ""));
            out.put("port", Json.integer(mail, "port", defaultPort(
                    Json.str(mail, "security", "ssl"))));
            out.put("security", Json.str(mail, "security", "ssl"));
            out.put("username", Json.str(mail, "username", ""));
            out.put("from", Json.str(mail, "from", ""));
            out.put("name", Json.str(mail, "name", "CodeForge AI"));
            out.put("default_to", Json.str(mail, "default_to", ""));
            out.put("secret_name", secret);
            out.put("has_password", keys.hasSecret(secret));
            out.put("max_attachment_bytes", MAX_ATTACHMENT_BYTES);
            out.put("note", "The password is stored in the local vault and is never returned.");
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", "Cannot read mail settings: " + message(ex));
        }
    }

    public synchronized Map<String, Object> saveConfig(Map<String, Object> request) {
        try {
            String secretName = Json.str(request, "secret_name", DEFAULT_SECRET).trim();
            if (secretName.isEmpty()) {
                secretName = DEFAULT_SECRET;
            }
            String password = Json.str(request, "password", "");
            if (!password.isBlank()) {
                Map<String, Object> saved = keys.setSecret(secretName, password);
                if (!Json.bool(saved, "ok", false)) {
                    return saved;
                }
            } else if (Json.bool(request, "clear_password", false)) {
                keys.deleteSecret(secretName);
            }
            Map<String, Object> doc = readDoc();
            Map<String, Object> mail = Json.asObject(doc.get("mail"));
            if (request.containsKey("host")) {
                mail.put("host", Json.str(request, "host", "").trim());
            }
            if (request.containsKey("port")) {
                mail.put("port", Math.max(1, Math.min(65535,
                        Json.integer(request, "port", 465))));
            }
            if (request.containsKey("security")) {
                String security = Json.str(request, "security", "ssl")
                        .trim().toLowerCase(Locale.ROOT);
                if (!List.of("ssl", "starttls", "none").contains(security)) {
                    return Json.map("ok", false,
                            "error", "security must be ssl, starttls or none");
                }
                mail.put("security", security);
            }
            if (request.containsKey("username")) {
                mail.put("username", Json.str(request, "username", "").trim());
            }
            if (request.containsKey("from")) {
                mail.put("from", Json.str(request, "from", "").trim());
            }
            if (request.containsKey("name")) {
                mail.put("name", Json.str(request, "name", "CodeForge AI").trim());
            }
            if (request.containsKey("default_to")) {
                mail.put("default_to", Json.str(request, "default_to", "").trim());
            }
            mail.put("secret_name", secretName);
            doc.put("mail", mail);
            writeDoc(doc);
            Map<String, Object> out = new LinkedHashMap<>(configView());
            out.put("message", "Mail settings saved");
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", "Cannot save mail settings: " + message(ex));
        }
    }

    public Map<String, Object> send(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> config = configView();
            if (!Json.bool(config, "ok", false)) {
                return config;
            }
            String host = Json.str(config, "host", "");
            if (host.isBlank()) {
                return Json.map("ok", false,
                        "error", "SMTP host is not configured. Open Mail → Settings.");
            }
            String from = Json.str(config, "from", Json.str(config, "username", ""));
            if (from.isBlank()) {
                return Json.map("ok", false, "error", "Sender address (from) is required");
            }
            String secretName = Json.str(config, "secret_name", DEFAULT_SECRET);
            String password = keys.secretValue(secretName);
            String username = Json.str(config, "username", "");
            if (!username.isBlank() && password.isBlank()) {
                return Json.map("ok", false,
                        "error", "SMTP password is missing. Save it in Mail → Settings.");
            }
            List<String> to = addresses(Json.str(request, "to", ""));
            if (to.isEmpty()) {
                return Json.map("ok", false, "error", "At least one recipient (to) is required");
            }
            List<String> cc = addresses(Json.str(request, "cc", ""));
            List<String> bcc = addresses(Json.str(request, "bcc", ""));
            String subject = Json.str(request, "subject", "CodeForge AI message");
            String text = Json.str(request, "text", "");
            String html = Json.str(request, "html", "");
            List<Path> attachments = new ArrayList<>();
            long total = 0;
            Object rawAttachments = request.get("attachments");
            if (rawAttachments instanceof List<?> list) {
                for (Object raw : list) {
                    String value = raw == null ? "" : String.valueOf(raw).trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    Path path = resolve(value);
                    if (!Files.isRegularFile(path)) {
                        return Json.map("ok", false, "error", "Attachment not found: " + path);
                    }
                    total += Files.size(path);
                    if (total > MAX_ATTACHMENT_BYTES) {
                        return Json.map("ok", false,
                                "error", "Attachments exceed 25 MB in total");
                    }
                    attachments.add(path);
                }
            }
            String security = Json.str(config, "security", "ssl");
            int port = Json.integer(config, "port", defaultPort(security));
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.connectiontimeout", "20000");
            props.put("mail.smtp.timeout", "45000");
            props.put("mail.smtp.writetimeout", "45000");
            props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
            if ("ssl".equals(security)) {
                props.put("mail.smtp.ssl.enable", "true");
            } else if ("starttls".equals(security)) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
            MimeMessage message = new MimeMessage(session);
            String displayName = Json.str(config, "name", "CodeForge AI");
            message.setFrom(new InternetAddress(from, displayName, StandardCharsets.UTF_8.name()));
            for (String address : to) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(address));
            }
            for (String address : cc) {
                message.addRecipient(Message.RecipientType.CC, new InternetAddress(address));
            }
            for (String address : bcc) {
                message.addRecipient(Message.RecipientType.BCC, new InternetAddress(address));
            }
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            if (!html.isBlank()) {
                message.setContent(html, "text/html; charset=UTF-8");
            } else {
                message.setText(text, StandardCharsets.UTF_8.name());
            }
            if (!attachments.isEmpty()) {
                Multipart multipart = new MimeMultipart();
                MimeBodyPart body = new MimeBodyPart();
                if (!html.isBlank()) {
                    body.setContent(html, "text/html; charset=UTF-8");
                } else {
                    body.setText(text, StandardCharsets.UTF_8.name());
                }
                multipart.addBodyPart(body);
                for (Path path : attachments) {
                    MimeBodyPart part = new MimeBodyPart();
                    part.attachFile(path.toFile());
                    part.setFileName(MimeUtility.encodeText(path.getFileName().toString(),
                            StandardCharsets.UTF_8.name(), null));
                    multipart.addBodyPart(part);
                }
                message.setContent(multipart);
            }
            message.saveChanges();
            String messageId = message.getMessageID();
            Transport.send(message);
            return Json.map(
                    "ok", true,
                    "message", "Email sent to " + String.join(", ", to),
                    "to", to,
                    "cc", cc,
                    "bcc_count", bcc.size(),
                    "attachments", attachments.stream().map(Path::toString).toList(),
                    "message_id", messageId == null ? "" : messageId,
                    "host", host,
                    "port", port,
                    "security", security,
                    "duration_ms", System.currentTimeMillis() - started);
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex),
                    "duration_ms", System.currentTimeMillis() - started);
        }
    }

    public Map<String, Object> test(Map<String, Object> request) {
        Map<String, Object> config = configView();
        String target = Json.str(request, "to", "");
        if (target.isBlank()) {
            target = Json.str(config, "default_to", "");
        }
        if (target.isBlank()) {
            target = Json.str(config, "from", "");
        }
        if (target.isBlank()) {
            return Json.map("ok", false, "error", "Set a recipient or the from address first");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("to", target);
        message.put("subject", "CodeForge AI SMTP test");
        message.put("text", "This is a test message sent by CodeForge AI.\n\n"
                + "If you can read this, SMTP is configured correctly.");
        return send(message);
    }

    private static List<String> addresses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String value = part.trim();
            if (value.contains("@") && !value.contains(" ")) {
                out.add(value);
            }
        }
        return out;
    }

    private Path resolve(String value) {
        String raw = value.trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.equals("~")) {
            raw = System.getProperty("user.home", "");
        } else if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            raw = System.getProperty("user.home", "") + raw.substring(1);
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static int defaultPort(String security) {
        return "ssl".equals(security) ? 465 : "starttls".equals(security) ? 587 : 25;
    }

    private Map<String, Object> readDoc() throws Exception {
        if (!Files.isRegularFile(configFile)) {
            return new LinkedHashMap<>();
        }
        return Json.asObject(Json.parse(Files.readString(configFile, StandardCharsets.UTF_8)));
    }

    private void writeDoc(Map<String, Object> doc) throws Exception {
        Files.writeString(configFile, Json.writePretty(doc), StandardCharsets.UTF_8);
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}