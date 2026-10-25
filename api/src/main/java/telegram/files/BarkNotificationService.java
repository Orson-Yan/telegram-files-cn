package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.impl.ArchiveOperationsRepository;
import telegram.files.share.security.AesGcmSecretStore;
import telegram.files.share.security.SecretEnvelope;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Persisted incident transitions, bounded retries and masked notification configuration. */
public final class BarkNotificationService {
    static final Set<String> EVENTS = Set.of("UNCONFIRMED", "FAILED", "CONNECTION", "LOGIN", "HISTORY", "STALLED");
    private final Vertx vertx;
    private final Supplier<TelegramHealthMonitor> health;
    private final HttpClient http;
    private long timer;
    private boolean busy;

    public BarkNotificationService(Vertx vertx, Supplier<TelegramHealthMonitor> health) {
        this.vertx = vertx; this.health = health; this.http = vertx.createHttpClient();
    }

    private ArchiveOperationsRepository store() { return DataVerticle.archiveOperations; }

    static JsonObject defaults() {
        return JsonObject.of("enabled", false, "serverUrl", "https://api.day.app", "siteUrl", "",
                "unconfirmedMinutes", 5, "connectionMinutes", 2, "stalledMinutes", 15,
                "repeatMinutes", 0, "recovery", true, "events", new JsonArray(EVENTS.stream().sorted().toList()));
    }

    private Future<JsonObject> configuration() {
        return store().query("SELECT * FROM notification_settings WHERE id = 1", Tuple.tuple()).map(rows -> {
            if (rows.isEmpty()) return defaults().put("encryptedKey", "");
            JsonObject row = rows.getJsonObject(0);
            return defaults().mergeIn(new JsonObject(row.getString("config_json")))
                    .put("encryptedKey", row.getString("encrypted_key", ""));
        });
    }

    public Future<JsonObject> settings() {
        return configuration().compose(config -> store().query("""
                SELECT incident_key, state, first_seen_at, last_sent_at, last_error, attempts
                FROM notification_incident ORDER BY first_seen_at DESC LIMIT 20
                """, Tuple.tuple()).map(incidents -> {
            config.put("keyConfigured", !config.getString("encryptedKey", "").isBlank());
            config.remove("encryptedKey"); config.put("incidents", incidents); return config;
        }));
    }

    public Future<JsonObject> save(JsonObject input) {
        if (input == null) return Future.failedFuture("Notification settings are required");
        JsonObject clean = defaults();
        try {
            clean.put("enabled", input.getBoolean("enabled", false));
            clean.put("serverUrl", validUrl(input.getString("serverUrl", "https://api.day.app"), false));
            clean.put("siteUrl", validUrl(input.getString("siteUrl", ""), true));
            for (String name : List.of("unconfirmedMinutes", "connectionMinutes", "stalledMinutes")) {
                int value = input.getInteger(name, clean.getInteger(name));
                if (value < 1 || value > 1440) throw new IllegalArgumentException("Alert delays must be between 1 and 1440 minutes");
                clean.put(name, value);
            }
            int repeat = input.getInteger("repeatMinutes", 0);
            if (repeat != 0 && (repeat < 5 || repeat > 1440)) throw new IllegalArgumentException("Repeat interval must be zero or 5 to 1440 minutes");
            clean.put("repeatMinutes", repeat).put("recovery", input.getBoolean("recovery", true));
            JsonArray events = input.getJsonArray("events", clean.getJsonArray("events"));
            for (Object event : events) if (!(event instanceof String) || !EVENTS.contains(event)) throw new IllegalArgumentException("Invalid notification event");
            clean.put("events", events);
        } catch (RuntimeException invalid) { return Future.failedFuture(invalid); }
        return configuration().compose(existing -> {
            String key = input.getString("deviceKey", "").trim();
            String encrypted = existing.getString("encryptedKey", "");
            try {
                if (input.getBoolean("clearKey", false)) encrypted = "";
                if (!key.isBlank()) {
                    if (key.length() > 512 || key.chars().anyMatch(Character::isWhitespace)) return Future.failedFuture("Invalid Bark device key");
                    encrypted = JsonObject.mapFrom(cipher().encrypt(key.getBytes(StandardCharsets.UTF_8))).encode();
                }
            } catch (Exception failure) { return Future.failedFuture("Could not securely save the Bark device key"); }
            if (clean.getBoolean("enabled") && encrypted.isBlank()) return Future.failedFuture("A Bark device key is required");
            String saved = encrypted;
            return store().execute("""
                    INSERT INTO notification_settings (id, config_json, encrypted_key)
                    SELECT 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM notification_settings WHERE id = 1)
                    """, Tuple.of(clean.encode(), saved))
                    .compose(_ -> store().execute("UPDATE notification_settings SET config_json = ?, encrypted_key = ? WHERE id = 1", Tuple.of(clean.encode(), saved)))
                    .compose(_ -> store().execute("UPDATE notification_incident SET attempts = 0, next_attempt_at = 0, last_error = NULL WHERE state IN ('OPEN', 'RECOVERY') AND last_error IS NOT NULL", Tuple.tuple()))
                    .compose(_ -> settings());
        });
    }

    private static String validUrl(String value, boolean optional) {
        if (optional && value.isBlank()) return "";
        URI uri = URI.create(value.trim());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Enter an HTTP or HTTPS address without credentials, query or fragment");
        }
        return uri.toString().replaceAll("/+$", "");
    }

    private static AesGcmSecretStore cipher() throws Exception {
        Path path = Path.of(Config.APP_ROOT, "notification-master.key");
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            try {
                Files.write(path, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) { /* Windows uses the data directory ACL. */ }
            } catch (java.nio.file.FileAlreadyExistsException ignored) { /* Another settings request created it. */ }
        }
        byte[] key = Files.readAllBytes(path);
        if (key.length != 32) throw new IllegalStateException("Notification encryption key is invalid");
        return new AesGcmSecretStore(Map.of(1, new SecretKeySpec(key, "AES")), 1);
    }

    public Future<Void> test() {
        return configuration().compose(config -> send(config, JsonObject.of("title", "通知测试", "body", "通知通道已连接，后续需要处理的异常会推送到此设备。")));
    }

    Future<Void> send(JsonObject config, JsonObject message) {
        String key;
        try {
            String envelope = config.getString("encryptedKey", "");
            if (envelope.isBlank()) return Future.failedFuture("A Bark device key is required");
            key = new String(cipher().decrypt(new JsonObject(envelope).mapTo(SecretEnvelope.class)), StandardCharsets.UTF_8);
        } catch (Exception failure) { return Future.failedFuture("Could not read the saved Bark device key"); }
        JsonObject payload = message.copy().put("device_key", key).put("group", "文件管理异常");
        String site = config.getString("siteUrl", "");
        String path = payload.getString("path", "/"); payload.remove("path");
        if (!site.isBlank()) payload.put("url", site + path);
        return http.request(new RequestOptions().setMethod(HttpMethod.POST)
                        .setAbsoluteURI(config.getString("serverUrl") + "/push").setTimeout(8_000))
                .compose(request -> request.putHeader("Content-Type", "application/json; charset=utf-8").send(Buffer.buffer(payload.encode())))
                .compose(response -> response.body().compose(body -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) return Future.failedFuture("Bark HTTP " + response.statusCode());
                    try {
                        if (new JsonObject(body).getInteger("code", 0) != 200) return Future.failedFuture("Bark rejected the notification");
                    } catch (RuntimeException invalid) { return Future.failedFuture("Bark returned an invalid response"); }
                    return Future.succeededFuture();
                })).timeout(10, TimeUnit.SECONDS).<Void>mapEmpty().recover(_ -> Future.failedFuture("Bark notification failed; check the server address, device key and network"));
    }

    public void start() { timer = vertx.setPeriodic(0, 30_000, _ -> tick()); }
    public void stop() { if (timer != 0) vertx.cancelTimer(timer); http.close(); }

    Future<Void> tick() {
        if (busy || store() == null) return Future.succeededFuture();
        busy = true;
        return configuration().compose(config -> {
            if (!config.getBoolean("enabled", false)) return Future.succeededFuture();
            return detect(config).compose(issues -> persistAndDispatch(config, issues));
        }).onComplete(_ -> busy = false);
    }

    private record Issue(String key, String type, long since, long delay, JsonObject payload) {}

    private Future<Map<String, Issue>> detect(JsonObject config) {
        Map<String, Issue> issues = new HashMap<>();
        Set<String> selected = new HashSet<>(); config.getJsonArray("events").forEach(event -> selected.add((String) event));
        Set<Long> monitored = new HashSet<>();
        var rules = AutomationsHolder.INSTANCE.autoRecords().getArchiveEnabledItems();
        rules.forEach(rule -> monitored.add(rule.telegramId));
        long now = System.currentTimeMillis();
        health.get().snapshots().forEach(value -> {
            JsonObject account = (JsonObject) value;
            long id = Long.parseLong(String.valueOf(account.getValue("telegramId")));
            String status = account.getString("status");
            if (!("ERROR".equals(status) || "INACTIVE".equals(status)) || ("INACTIVE".equals(status) && !monitored.contains(id))) return;
            String type = "INACTIVE".equals(status) ? "LOGIN" : "CONNECTION";
            if (!selected.contains(type)) return;
            String key = "account:" + id + ":" + type;
            issues.put(key, new Issue(key, type, Math.max(1, number(account, "unhealthySince", now)),
                    "LOGIN".equals(type) ? 0 : config.getInteger("connectionMinutes") * 60_000L,
                    JsonObject.of("title", "LOGIN".equals(type) ? "账号需要重新登录" : "账号连接异常", "body",
                            "账号：" + account.getString("accountName", String.valueOf(id)) + "\n请打开连接检查页面处理。", "path", "/")));
        });
        Future<Void> result = Future.succeededFuture();
        for (var rule : rules) result = result.compose(_ -> Future.all(
                DataVerticle.cloudArchiveRepository.routeStatistics(rule.telegramId, rule.chatId, rule.archive.rule.targetChatId),
                DataVerticle.cloudArchiveHistoryRepository.findActive(rule.telegramId, rule.chatId, rule.archive.rule.targetChatId)).compose(values -> {
            JsonObject stats = values.resultAt(0);
            telegram.files.repository.CloudArchiveHistoryJob activeHistory = values.resultAt(1);
            boolean historyWaiting = activeHistory != null && ("PAUSED".equals(activeHistory.status())
                    || (activeHistory.dailyLimit() > 0 && activeHistory.dailyForwardedCount() >= activeHistory.dailyLimit()
                    && java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().equals(activeHistory.dailyDate())));
            String first = stats.getString("firstStatus", "");
            String type = "UNKNOWN".equals(first) ? "UNCONFIRMED" : "FAILED".equals(first) ? "FAILED" : "";
            long since = number(stats, "firstUpdatedAt", now);
            if (type.isBlank() && !historyWaiting && number(stats, "due", 0) > 0
                    && !AutoCloudArchiveVerticle.getActiveCooldowns().containsKey(rule.telegramId)
                    && TelegramVerticles.get(rule.telegramId).map(account -> "ready".equals(account.connectionSnapshot().getString("connectionState"))
                            && !account.connectionSnapshot().getBoolean("sleeping", false)).orElse(false)) {
                long progress = Math.max(number(stats, "lastSuccessAt", 0), number(stats, "oldestDueAt", now));
                if (now - progress >= config.getInteger("stalledMinutes") * 60_000L) { type = "STALLED"; since = progress; }
            }
            if (!type.isBlank() && selected.contains(type)) {
                String key = "route:" + rule.telegramId + ":" + rule.chatId + ":" + rule.archive.rule.targetChatId + ":" + type;
                long delay = "UNCONFIRMED".equals(type) ? config.getInteger("unconfirmedMinutes") * 60_000L : 0;
                String title = "UNCONFIRMED".equals(type) ? "转发结果待核实，需要处理" : "FAILED".equals(type) ? "转发出现永久失败，需要处理" : "转发长时间没有进展";
                issues.put(key, new Issue(key, type, since, delay, JsonObject.of("title", title,
                        "body", "账号：" + rule.telegramId + "\n规则：" + rule.chatId + " → " + rule.archive.rule.targetChatId
                                + "\n后续待发：" + number(stats, "pending", 0) + " 条\n请打开异常处理面板核实。",
                        "path", "/cloud-archive?record=" + stats.getString("firstRecordId", ""))));
            }
            if (!selected.contains("HISTORY")) return Future.succeededFuture();
            return store().query("SELECT id, status, updated_at FROM telegram_archive_history_job WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ? ORDER BY created_at DESC LIMIT 1",
                    Tuple.of(rule.telegramId, rule.chatId, rule.archive.rule.targetChatId)).map(jobs -> {
                JsonObject job = jobs.isEmpty() ? null : jobs.getJsonObject(0);
                if (job != null && "FAILED".equals(job.getString("status"))) {
                    String key = "history:" + job.getString("id");
                    issues.put(key, new Issue(key, "HISTORY", number(job, "updated_at", now), 0, JsonObject.of("title", "历史归档任务失败",
                            "body", "历史任务已停止，请打开历史任务页面检查。", "path", "/cloud-archive")));
                }
                return null;
            });
        }));
        return result.map(issues);
    }

    private Future<Void> persistAndDispatch(JsonObject config, Map<String, Issue> issues) {
        return store().query("SELECT * FROM notification_incident", Tuple.tuple()).compose(rows -> {
            Map<String, JsonObject> previous = new HashMap<>(); rows.forEach(value -> { JsonObject row = (JsonObject) value; previous.put(row.getString("incident_key"), row); });
            Future<Void> result = Future.succeededFuture();
            for (Issue issue : issues.values()) {
                JsonObject prior = previous.get(issue.key());
                if (prior == null) {
                    result = result.compose(_ -> store().execute("""
                            INSERT INTO notification_incident (incident_key, state, payload, first_seen_at)
                            VALUES (?, 'OPEN', ?, ?)
                            """, Tuple.of(issue.key(), issue.payload().encode(), issue.since())).mapEmpty());
                } else if (!"OPEN".equals(prior.getString("state"))) {
                    result = result.compose(_ -> store().execute("""
                            UPDATE notification_incident SET state = 'OPEN', payload = ?, first_seen_at = ?,
                                last_sent_at = 0, next_attempt_at = 0, attempts = 0, notified = 0, last_error = NULL WHERE incident_key = ?
                            """, Tuple.of(issue.payload().encode(), issue.since(), issue.key())).mapEmpty());
                }
            }
            for (JsonObject prior : previous.values()) if (!issues.containsKey(prior.getString("incident_key")) && "OPEN".equals(prior.getString("state"))) {
                String oldKey = prior.getString("incident_key");
                String base = oldKey.substring(0, oldKey.lastIndexOf(':'));
                String event = oldKey.startsWith("history:") ? "HISTORY" : oldKey.substring(oldKey.lastIndexOf(':') + 1);
                boolean monitorEnabled = !oldKey.startsWith("route:") || AutomationsHolder.INSTANCE.autoRecords().getArchiveEnabledItems().stream()
                        .anyMatch(rule -> base.equals("route:" + rule.telegramId + ":" + rule.chatId + ":" + rule.archive.rule.targetChatId));
                boolean replaced = issues.keySet().stream().anyMatch(key -> !key.equals(oldKey) && key.startsWith(base + ":"));
                String next = number(prior, "notified", 0) == 1 && config.getBoolean("recovery", true)
                        && config.getJsonArray("events").contains(event) && monitorEnabled && !replaced ? "RECOVERY" : "CLOSED";
                result = result.compose(_ -> store().execute("UPDATE notification_incident SET state = ?, attempts = 0, next_attempt_at = 0 WHERE incident_key = ?",
                        Tuple.of(next, prior.getString("incident_key"))).mapEmpty());
            }
            return result.compose(_ -> store().query("SELECT * FROM notification_incident WHERE state IN ('OPEN', 'RECOVERY')", Tuple.tuple())).compose(current -> {
                Future<Void> sends = Future.succeededFuture();
                long now = System.currentTimeMillis();
                for (Object value : current) {
                    JsonObject incident = (JsonObject) value;
                    String key = incident.getString("incident_key");
                    boolean recovery = "RECOVERY".equals(incident.getString("state"));
                    Issue issue = issues.get(key);
                    if (!recovery && (issue == null || now - number(incident, "first_seen_at", now) < issue.delay())) continue;
                    if (number(incident, "attempts", 0) >= 3 || number(incident, "next_attempt_at", 0) > now) continue;
                    long lastSent = number(incident, "last_sent_at", 0);
                    int repeat = config.getInteger("repeatMinutes", 0);
                    if (!recovery && number(incident, "notified", 0) == 1 && (repeat == 0 || now - lastSent < repeat * 60_000L)) continue;
                    JsonObject payload = new JsonObject(incident.getString("payload"));
                    if (recovery) payload.put("title", "异常已解除").put("body", "此前的异常已解除，请在页面查看当前状态。");
                    sends = sends.compose(_ -> send(config, payload).compose(_ -> store().execute("""
                            UPDATE notification_incident SET state = ?, last_sent_at = ?, notified = 1,
                                attempts = 0, next_attempt_at = 0, last_error = NULL WHERE incident_key = ?
                            """, Tuple.of(recovery ? "CLOSED" : "OPEN", System.currentTimeMillis(), key)).<Void>mapEmpty())
                            .recover(_ -> store().execute("""
                                    UPDATE notification_incident SET attempts = attempts + 1, next_attempt_at = ?,
                                        last_error = 'Bark push failed; check notification settings' WHERE incident_key = ?
                                    """, Tuple.of(System.currentTimeMillis() + (number(incident, "attempts", 0) + 1) * 60_000, key)).<Void>mapEmpty()));
                }
                return sends;
            });
        });
    }

    private static long number(JsonObject value, String key, long fallback) {
        Object number = value.getValue(key); return number instanceof Number n && n.longValue() > 0 ? n.longValue() : fallback;
    }
}
