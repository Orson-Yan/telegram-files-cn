package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.Definition;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable delivery evidence, immutable success accounting and operator audit. */
public final class ArchiveOperationsRepository extends AbstractSqlRepository {
    private final Pool pool;

    public ArchiveOperationsRepository(Pool pool) { super(pool); this.pool = pool; }

    public static final class StorageDefinition implements Definition {
        public String getScheme() { return SCHEMAS.getFirst(); }
        public static int tableCount() { return SCHEMAS.size(); }
        public Future<Void> createTable(SqlClient client) {
            Future<Void> result = Future.succeededFuture();
            for (String sql : SCHEMAS) result = result.compose(_ -> client.query(sql).execute().mapEmpty());
            for (String index : List.of(
                    "CREATE INDEX idx_delivery_record ON archive_delivery_attempt (record_id, started_at)",
                    "CREATE INDEX idx_delivery_temporary ON archive_delivery_attempt (telegram_id, target_chat_id, temporary_message_id)",
                    "CREATE INDEX idx_archive_audit_record ON archive_operator_audit (record_id, action)",
                    "CREATE INDEX idx_archive_success_time ON archive_success (delivered_at, telegram_id)",
                    "CREATE UNIQUE INDEX idx_archive_success_route ON archive_success (telegram_id, source_chat_id, source_message_id, target_chat_id)")) {
                result = result.compose(_ -> client.query(index).execute().recover(failure -> {
                    String message = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
                    return message.contains("already exists") || message.contains("duplicate key name")
                            ? Future.succeededFuture() : Future.failedFuture(failure);
                }).mapEmpty());
            }
            return result;
        }
    }

    private static final List<String> SCHEMAS = List.of(
            """
            CREATE TABLE IF NOT EXISTS archive_delivery_attempt (
                id VARCHAR(64) PRIMARY KEY, record_id VARCHAR(64) NOT NULL,
                telegram_id BIGINT NOT NULL, source_chat_id BIGINT NOT NULL,
                source_message_id BIGINT NOT NULL, target_chat_id BIGINT NOT NULL,
                mode VARCHAR(32) NOT NULL, temporary_message_id BIGINT,
                target_message_id BIGINT, state VARCHAR(32) NOT NULL,
                started_at BIGINT NOT NULL, confirmed_at BIGINT NOT NULL DEFAULT 0,
                checked_at BIGINT NOT NULL DEFAULT 0, error_message VARCHAR(1024)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS archive_success (
                event_key VARCHAR(255) PRIMARY KEY, record_id VARCHAR(64) NOT NULL,
                telegram_id BIGINT NOT NULL, source_chat_id BIGINT NOT NULL,
                source_message_id BIGINT NOT NULL, target_chat_id BIGINT NOT NULL,
                target_message_id BIGINT NOT NULL, mode VARCHAR(32) NOT NULL,
                delivered_at BIGINT NOT NULL, estimated INT NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS archive_operator_audit (
                id VARCHAR(64) PRIMARY KEY, record_id VARCHAR(64) NOT NULL,
                action VARCHAR(32) NOT NULL, reason VARCHAR(1024) NOT NULL,
                target_message_id BIGINT, created_at BIGINT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS notification_incident (
                incident_key VARCHAR(255) PRIMARY KEY, state VARCHAR(32) NOT NULL,
                payload TEXT NOT NULL, first_seen_at BIGINT NOT NULL,
                last_sent_at BIGINT NOT NULL DEFAULT 0, next_attempt_at BIGINT NOT NULL DEFAULT 0,
                attempts INT NOT NULL DEFAULT 0, notified INT NOT NULL DEFAULT 0,
                last_error VARCHAR(255)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS notification_settings (
                id INT PRIMARY KEY, config_json TEXT NOT NULL, encrypted_key TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS archive_delivery_update (
                update_key VARCHAR(255) PRIMARY KEY, telegram_id BIGINT NOT NULL,
                target_chat_id BIGINT NOT NULL, temporary_message_id BIGINT NOT NULL,
                target_message_id BIGINT NOT NULL, delivered_at BIGINT NOT NULL,
                state VARCHAR(32) NOT NULL, error_message VARCHAR(1024)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS archive_recovery_state (
                record_id VARCHAR(64) PRIMARY KEY, signature VARCHAR(512) NOT NULL,
                rounds INT NOT NULL DEFAULT 0, checked_at BIGINT NOT NULL,
                next_check_at BIGINT NOT NULL DEFAULT 0
            )
            """);

    public Future<Void> backfill() {
        // A portable anti-join also keeps this migration safe on every restart.
        String eventKey = telegram.files.Config.isMysql()
                ? "CONCAT(r.telegram_id, ':', r.source_chat_id, ':', r.source_message_id, ':', r.target_chat_id)"
                : "CAST(r.telegram_id AS VARCHAR(32)) || ':' || CAST(r.source_chat_id AS VARCHAR(32)) || ':' || CAST(r.source_message_id AS VARCHAR(32)) || ':' || CAST(r.target_chat_id AS VARCHAR(32))";
        return preparedQuery("""
                INSERT INTO archive_success (event_key, record_id, telegram_id, source_chat_id, source_message_id,
                    target_chat_id, target_message_id, mode, delivered_at, estimated)
                SELECT %s, r.id, r.telegram_id, r.source_chat_id, r.source_message_id,
                    r.target_chat_id, r.target_message_id, r.mode, r.updated_at, 1 FROM telegram_archive_record r
                WHERE r.status = 'COMPLETED' AND r.target_message_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM archive_success s WHERE s.telegram_id = r.telegram_id
                    AND s.source_chat_id = r.source_chat_id AND s.source_message_id = r.source_message_id
                    AND s.target_chat_id = r.target_chat_id)
                """.formatted(eventKey)).execute().mapEmpty();
    }

    public Future<CloudArchiveRecord> find(String id) {
        return preparedQuery("SELECT * FROM telegram_archive_record WHERE id = ?").execute(Tuple.of(id))
                .map(rows -> rows.iterator().hasNext() ? CloudArchiveRecord.from(rows.iterator().next()) : null);
    }

    public Future<Boolean> wasDelivered(long account, long source, long message, long target) {
        return preparedQuery("SELECT event_key FROM archive_success WHERE event_key = ?")
                .execute(Tuple.of(key(account, source, message, target))).map(rows -> rows.size() > 0);
    }

    public Future<Map<Long, String>> begin(long account, long source, long target, long[] messages) {
        Map<Long, String> attempts = new LinkedHashMap<>();
        Future<Void> result = Future.succeededFuture();
        for (long message : messages) {
            result = result.compose(_ -> preparedQuery("""
                    SELECT * FROM telegram_archive_record WHERE telegram_id = ? AND source_chat_id = ?
                        AND source_message_id = ? AND target_chat_id = ? AND status = 'SENDING'
                    """).execute(Tuple.of(account, source, message, target)).compose(rows -> {
                if (rows.size() == 0) return Future.succeededFuture();
                CloudArchiveRecord record = CloudArchiveRecord.from(rows.iterator().next());
                String id = UUID.randomUUID().toString();
                return preparedQuery("""
                        INSERT INTO archive_delivery_attempt
                            (id, record_id, telegram_id, source_chat_id, source_message_id, target_chat_id,
                             mode, state, started_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?)
                        """).execute(Tuple.of(id, record.id(), account, source, message, target, record.mode(), System.currentTimeMillis()))
                        .onSuccess(_ -> attempts.put(message, id)).mapEmpty();
            }));
        }
        return result.map(attempts);
    }

    public Future<Void> pending(String attempt, long temporaryId) {
        return preparedQuery("""
                UPDATE archive_delivery_attempt SET temporary_message_id = ?, state = 'PENDING'
                WHERE id = ? AND state = 'SUBMITTED'
                """).execute(Tuple.of(temporaryId, attempt))
                .compose(_ -> preparedQuery("""
                        SELECT u.* FROM archive_delivery_update u JOIN archive_delivery_attempt a
                          ON a.telegram_id = u.telegram_id AND a.target_chat_id = u.target_chat_id
                            AND a.temporary_message_id = u.temporary_message_id WHERE a.id = ?
                        """).execute(Tuple.of(attempt)))
                .compose(rows -> {
                    if (rows.size() == 0) return Future.succeededFuture();
                    Row update = rows.iterator().next();
                    return "CONFIRMED".equals(update.getString("state"))
                            ? confirm(attempt, ((Number) update.getValue("target_message_id")).longValue(), ((Number) update.getValue("delivered_at")).longValue())
                            : failed(attempt, update.getString("error_message"));
                });
    }

    public Future<Void> rememberUpdate(long account, long chat, long temporary, long target, long at, String state, String error) {
        String key = account + ":" + chat + ":" + temporary;
        return preparedQuery("""
                INSERT INTO archive_delivery_update (update_key, telegram_id, target_chat_id, temporary_message_id,
                    target_message_id, delivered_at, state, error_message)
                SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM archive_delivery_update WHERE update_key = ?)
                """).execute(Tuple.of(key, account, chat, temporary, target, at, state,
                        error == null ? null : error.substring(0, Math.min(1024, error.length())), key)).mapEmpty();
    }

    public Future<JsonArray> attempts(String recordId) {
        return preparedQuery("SELECT * FROM archive_delivery_attempt WHERE record_id = ? ORDER BY started_at DESC")
                .execute(Tuple.of(recordId)).map(rows -> {
                    JsonArray result = new JsonArray();
                    rows.forEach(row -> result.add(row.toJson()));
                    return result;
                });
    }

    public Future<JsonArray> audit(String recordId) {
        return preparedQuery("SELECT * FROM archive_operator_audit WHERE record_id = ? ORDER BY created_at DESC")
                .execute(Tuple.of(recordId)).map(rows -> {
                    JsonArray result = new JsonArray(); rows.forEach(row -> result.add(row.toJson())); return result;
                });
    }

    public Future<List<JsonObject>> byTemporaryId(long account, long target, long temporaryId) {
        return preparedQuery("""
                SELECT * FROM archive_delivery_attempt WHERE telegram_id = ? AND target_chat_id = ?
                    AND temporary_message_id = ? AND state IN ('PENDING', 'SUBMITTED')
                """).execute(Tuple.of(account, target, temporaryId)).map(rows -> {
                    List<JsonObject> result = new ArrayList<>(); rows.forEach(row -> result.add(row.toJson())); return result;
                });
    }

    public Future<Void> confirm(String attemptId, long targetId, long deliveredAt) {
        return pool.withTransaction(connection -> preparedQuery(connection,
                "SELECT * FROM archive_delivery_attempt WHERE id = ?").execute(Tuple.of(attemptId)).compose(rows -> {
            if (rows.size() == 0) return Future.succeededFuture();
            Row attempt = rows.iterator().next();
            String recordId = attempt.getString("record_id");
            return preparedQuery(connection, """
                    UPDATE archive_delivery_attempt SET state = 'CONFIRMED', target_message_id = ?, confirmed_at = ?
                    WHERE id = ?
                    """).execute(Tuple.of(targetId, deliveredAt, attemptId))
                    .compose(_ -> preparedQuery(connection, "SELECT * FROM telegram_archive_record WHERE id = ?")
                            .execute(Tuple.of(recordId)))
                    .compose(records -> {
                        if (records.size() == 0) return Future.succeededFuture();
                        CloudArchiveRecord record = CloudArchiveRecord.from(records.iterator().next());
                        return saveSuccess(connection, record, targetId, deliveredAt, false)
                                .compose(_ -> preparedQuery(connection, """
                                        UPDATE telegram_archive_record SET status = 'COMPLETED', target_message_id = ?,
                                            last_error_code = NULL, last_error_message = NULL, updated_at = ?
                                        WHERE id = ? AND status IN ('SENDING', 'UNKNOWN', 'FAILED', 'CANCELLED')
                                        """).execute(Tuple.of(targetId, System.currentTimeMillis(), recordId))).mapEmpty();
                    });
        }));
    }

    public Future<Void> failed(String attemptId, String message) {
        String safe = message == null ? "Telegram delivery failed" : message.substring(0, Math.min(message.length(), 1024));
        return pool.withTransaction(connection -> preparedQuery(connection, """
                UPDATE archive_delivery_attempt SET state = 'FAILED', error_message = ? WHERE id = ? AND state <> 'CONFIRMED'
                """).execute(Tuple.of(safe, attemptId)).compose(_ -> preparedQuery(connection, """
                UPDATE telegram_archive_record SET status = 'FAILED', last_error_code = 'DELIVERY_FAILED',
                    last_error_message = ?, updated_at = ?
                WHERE status = 'UNKNOWN' AND id IN (SELECT record_id FROM archive_delivery_attempt WHERE id = ? AND state = 'FAILED')
                """).execute(Tuple.of(safe, System.currentTimeMillis(), attemptId))).mapEmpty());
    }

    public Future<Void> captureSuccess(String recordId, long targetId, long fallbackTime) {
        return find(recordId).compose(record -> record == null ? Future.succeededFuture()
                : saveSuccess(sqlClient, record, targetId, fallbackTime, false));
    }

    private Future<Void> saveSuccess(SqlClient client, CloudArchiveRecord record, long targetId, long at, boolean estimated) {
        String key = key(record.telegramId(), record.sourceChatId(), record.sourceMessageId(), record.targetChatId());
        return preparedQuery(client, """
                INSERT INTO archive_success (event_key, record_id, telegram_id, source_chat_id, source_message_id,
                    target_chat_id, target_message_id, mode, delivered_at, estimated)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM archive_success WHERE event_key = ?)
                """).execute(Tuple.of(key, record.id(), record.telegramId(), record.sourceChatId(), record.sourceMessageId(),
                        record.targetChatId(), targetId, record.mode(), at, estimated ? 1 : 0, key))
                .compose(_ -> estimated ? Future.succeededFuture() : preparedQuery(client, """
                        UPDATE archive_success SET delivered_at = ?, target_message_id = ?, estimated = 0
                        WHERE event_key = ? AND estimated = 1
                        """).execute(Tuple.of(at, targetId, key)).mapEmpty()).mapEmpty();
    }

    public Future<Void> manualComplete(CloudArchiveRecord record, long targetId, long deliveredAt, String reason) {
        return pool.withTransaction(connection -> preparedQuery(connection, """
                UPDATE telegram_archive_record SET status = 'COMPLETED', target_message_id = ?,
                    last_error_code = NULL, last_error_message = NULL, updated_at = ?
                WHERE id = ? AND status IN ('UNKNOWN', 'FAILED')
                """).execute(Tuple.of(targetId, System.currentTimeMillis(), record.id())).compose(rows -> {
            if (rows.rowCount() != 1) return Future.failedFuture("Record changed; refresh before resolving");
            return saveSuccess(connection, record, targetId, deliveredAt, false)
                    .compose(_ -> audit(connection, record.id(), "CONFIRM_SENT", reason, targetId));
        }));
    }

    public Future<Void> manualSkip(CloudArchiveRecord record, String reason) {
        return pool.withTransaction(connection -> preparedQuery(connection, """
                UPDATE telegram_archive_record SET status = 'SKIPPED', last_error_code = 'USER_SKIPPED',
                    last_error_message = ?, updated_at = ? WHERE id = ? AND status IN ('UNKNOWN', 'FAILED')
                """).execute(Tuple.of(reason, System.currentTimeMillis(), record.id())).compose(rows -> {
            if (rows.rowCount() != 1) return Future.failedFuture("Record changed; refresh before resolving");
            return audit(connection, record.id(), "SKIP", reason, null);
        }));
    }

    public Future<Void> audit(String recordId, String action, String reason) {
        return audit(sqlClient, recordId, action, reason, null);
    }

    private Future<Void> audit(SqlClient client, String recordId, String action, String reason, Long targetId) {
        return preparedQuery(client, """
                INSERT INTO archive_operator_audit (id, record_id, action, reason, target_message_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """).execute(Tuple.of(UUID.randomUUID().toString(), recordId, action, reason, targetId, System.currentTimeMillis())).mapEmpty();
    }

    public Future<List<String>> unresolved() {
        long now = System.currentTimeMillis();
        return preparedQuery("""
                SELECT r.id FROM telegram_archive_record r WHERE r.status = 'UNKNOWN'
                    AND EXISTS (SELECT 1 FROM archive_delivery_attempt a WHERE a.record_id = r.id
                        AND a.state = 'PENDING' AND a.checked_at < ?)
                ORDER BY r.source_message_id LIMIT 20
                """).execute(Tuple.of(now - 60_000)).map(rows -> {
                    List<String> result = new ArrayList<>(); rows.forEach(row -> result.add(row.getString("id"))); return result;
                });
    }

    public Future<Void> checked(String attempt) {
        return preparedQuery("UPDATE archive_delivery_attempt SET checked_at = ? WHERE id = ?")
                .execute(Tuple.of(System.currentTimeMillis(), attempt)).mapEmpty();
    }

    public Future<JsonObject> daily(LocalDate from, LocalDate to, ZoneId zone, long account, Long source, Long target) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 92) return Future.failedFuture("Select a range of 1 to 93 days");
        JsonArray days = new JsonArray();
        Future<Void> result = Future.succeededFuture();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            LocalDate current = day;
            StringBuilder scope = new StringBuilder(" WHERE delivered_at >= ? AND delivered_at < ?");
            Tuple values = Tuple.of(day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
            if (account != 0) { scope.append(" AND telegram_id = ?"); values.addLong(account); }
            if (source != null && source != 0) { scope.append(" AND source_chat_id = ?"); values.addLong(source); }
            if (target != null && target != 0) { scope.append(" AND target_chat_id = ?"); values.addLong(target); }
            result = result.compose(_ -> preparedQuery("""
                    SELECT COUNT(*) AS total, COUNT(CASE WHEN mode = 'COPY' THEN 1 END) AS copied,
                        COUNT(CASE WHEN mode = 'FORWARD' THEN 1 END) AS forwarded,
                        COUNT(CASE WHEN estimated = 1 THEN 1 END) AS estimated FROM archive_success
                    """ + scope).execute(values).onSuccess(rows -> {
                Row row = rows.iterator().next();
                days.add(new JsonObject().put("date", current.toString()).put("copied", number(row, "copied"))
                        .put("forwarded", number(row, "forwarded")).put("total", number(row, "total"))
                        .put("estimated", number(row, "estimated")));
            }).mapEmpty());
        }
        return result.compose(_ -> summary(account, source, target))
                .map(statistics -> JsonObject.of("timeZone", zone.getId(), "days", days, "statistics", statistics));
    }

    public Future<JsonArray> query(String sql, Tuple values) {
        return preparedQuery(sql).execute(values).map(rows -> {
            JsonArray result = new JsonArray(); rows.forEach(row -> result.add(row.toJson())); return result;
        });
    }

    public Future<Integer> execute(String sql, Tuple values) {
        return preparedQuery(sql).execute(values).map(rows -> rows.rowCount());
    }

    public Future<Integer> hideRecords(String selection, Tuple values) {
        String id = telegram.files.Config.isMysql() ? "CONCAT('hide:', r.id)" : "'hide:' || r.id";
        return execute("INSERT INTO archive_operator_audit (id, record_id, action, reason, created_at) SELECT " + id
                + ", r.id, 'HIDE_LOG', 'Log hidden; delivery and deduplication retained', " + System.currentTimeMillis()
                + " FROM (" + selection + ") r WHERE NOT EXISTS (SELECT 1 FROM archive_operator_audit a WHERE a.record_id = r.id AND a.action = 'HIDE_LOG')", values);
    }

    public Future<JsonObject> successCounts(long account, Long source, Long target) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        Tuple values = Tuple.of(today.atStartOfDay(zone).toInstant().toEpochMilli(), today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        String scope = scope(values, account, source, target);
        return preparedQuery("SELECT COUNT(*) AS completed, COUNT(CASE WHEN delivered_at >= ? AND delivered_at < ? THEN 1 END) AS today_completed FROM archive_success WHERE 1=1" + scope)
                .execute(values).map(rows -> JsonObject.of("completed", number(rows.iterator().next(), "completed"), "todayCompleted", number(rows.iterator().next(), "today_completed")));
    }

    public Future<JsonObject> summary(long account, Long source, Long target) {
        Tuple values = Tuple.tuple();
        return preparedQuery("""
                SELECT COUNT(CASE WHEN status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING') THEN 1 END) AS pending,
                    COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed,
                    COUNT(CASE WHEN status = 'UNKNOWN' THEN 1 END) AS unconfirmed,
                    COUNT(CASE WHEN status = 'SKIPPED' THEN 1 END) AS skipped
                FROM telegram_archive_record WHERE 1=1
                """ + scope(values, account, source, target)).execute(values).compose(rows -> {
            Row row = rows.iterator().next();
            JsonObject result = JsonObject.of("pending", number(row, "pending"), "failed", number(row, "failed"),
                    "unconfirmed", number(row, "unconfirmed"), "skipped", number(row, "skipped"));
            return successCounts(account, source, target).map(result::mergeIn);
        });
    }

    public Future<JsonObject> issues(long account, long source, long target, long after, int limit) {
        return preparedQuery("""
                SELECT id, source_message_id, status, updated_at, last_error_code FROM telegram_archive_record
                WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                  AND status IN ('UNKNOWN', 'FAILED') AND source_message_id > ?
                ORDER BY source_message_id, id LIMIT ?
                """).execute(Tuple.of(account, source, target, after, limit)).compose(rows -> {
            JsonArray items = new JsonArray();
            long cursor = after;
            for (Row row : rows) {
                cursor = ((Number) row.getValue("source_message_id")).longValue();
                items.add(JsonObject.of("id", row.getString("id"), "sourceMessageId", String.valueOf(cursor),
                        "status", row.getString("status"), "updatedAt", row.getValue("updated_at"), "errorCode", row.getString("last_error_code")));
            }
            String next = rows.size() == limit ? String.valueOf(cursor) : null;
            return preparedQuery("SELECT COUNT(*) AS total FROM telegram_archive_record WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ? AND status IN ('UNKNOWN', 'FAILED')")
                    .execute(Tuple.of(account, source, target)).map(count -> JsonObject.of("items", items, "total", number(count.iterator().next(), "total"), "nextCursor", next));
        });
    }

    public Future<JsonObject> skipSelected(long account, long source, long target, List<String> ids) {
        JsonArray results = new JsonArray();
        Future<Void> work = Future.succeededFuture();
        for (String id : ids) work = work.compose(_ -> find(id).compose(record -> {
            if (record == null || record.telegramId() != account || record.sourceChatId() != source || record.targetChatId() != target) {
                results.add(JsonObject.of("id", id, "result", "OUT_OF_SCOPE")); return Future.succeededFuture();
            }
            if (!List.of("UNKNOWN", "FAILED").contains(record.status())) {
                results.add(JsonObject.of("id", id, "result", "CHANGED")); return Future.succeededFuture();
            }
            return manualSkip(record, "用户在任务异常列表中选择跳过")
                    .onSuccess(_ -> { results.add(JsonObject.of("id", id, "result", "SKIPPED")); telegram.files.AutoCloudArchiveVerticle.QUEUED_RECORD_AT.remove(id); })
                    .recover(_ -> find(id).map(current -> {
                        results.add(JsonObject.of("id", id, "result", current != null && List.of("UNKNOWN", "FAILED").contains(current.status()) ? "ERROR" : "CHANGED"));
                        return null;
                    }));
        }).recover(_ -> { results.add(JsonObject.of("id", id, "result", "ERROR")); return Future.succeededFuture(); }));
        return work.map(_ -> JsonObject.of("results", results, "skipped", results.stream().filter(value -> "SKIPPED".equals(((JsonObject) value).getString("result"))).count()));
    }

    public Future<Void> rememberRecovery(String id, String signature, int rounds, long nextCheck) {
        long now = System.currentTimeMillis();
        return execute("INSERT INTO archive_recovery_state (record_id, signature, rounds, checked_at, next_check_at) SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM archive_recovery_state WHERE record_id = ?)",
                Tuple.of(id, signature, rounds, now, nextCheck, id))
                .compose(_ -> execute("UPDATE archive_recovery_state SET signature = ?, rounds = ?, checked_at = ?, next_check_at = ? WHERE record_id = ?", Tuple.of(signature, rounds, now, nextCheck, id))).mapEmpty();
    }

    public Future<Void> skipUnavailableSource(CloudArchiveRecord record) {
        return pool.withTransaction(connection -> preparedQuery(connection, """
                UPDATE telegram_archive_record SET status = 'SKIPPED', last_error_code = 'SOURCE_UNAVAILABLE',
                    last_error_message = 'Source message was removed or cannot be copied', updated_at = ?
                WHERE id = ? AND status = 'FAILED' AND updated_at = ?
                """).execute(Tuple.of(System.currentTimeMillis(), record.id(), record.updatedAt())).compose(rows -> rows.rowCount() == 0
                        ? Future.succeededFuture() : audit(connection, record.id(), "AUTO_SKIP", "来源消息已删除或明确不可转存", null)));
    }

    public Future<Boolean> resumeFailed(CloudArchiveRecord record, String signature, int rounds) {
        long now = System.currentTimeMillis();
        return pool.withTransaction(connection -> preparedQuery(connection, """
                UPDATE telegram_archive_record SET status = 'RETRY', attempt_count = 0, next_attempt_at = ?,
                    last_error_code = NULL, last_error_message = NULL, updated_at = ?
                WHERE id = ? AND status = 'FAILED' AND updated_at = ?
                """).execute(Tuple.of(now, now, record.id(), record.updatedAt())).compose(updated -> {
            if (updated.rowCount() == 0) return Future.succeededFuture(false);
            return preparedQuery(connection, "UPDATE archive_recovery_state SET signature = ?, rounds = ?, checked_at = ?, next_check_at = ? WHERE record_id = ?")
                    .execute(Tuple.of(signature, rounds, now, now + 15 * 60_000L, record.id()))
                    .compose(_ -> audit(connection, record.id(), "AUTO_RETRY", "自动检查确认发送条件可用后恢复重试", null)).map(true);
        }));
    }

    private static String scope(Tuple values, long account, Long source, Long target) {
        StringBuilder sql = new StringBuilder();
        if (account != 0) { sql.append(" AND telegram_id = ?"); values.addLong(account); }
        if (source != null && source != 0) { sql.append(" AND source_chat_id = ?"); values.addLong(source); }
        if (target != null && target != 0) { sql.append(" AND target_chat_id = ?"); values.addLong(target); }
        return sql.toString();
    }

    private static long number(Row row, String column) { return ((Number) row.getValue(column)).longValue(); }
    private static String key(long account, long source, long message, long target) { return account + ":" + source + ":" + message + ":" + target; }
}
