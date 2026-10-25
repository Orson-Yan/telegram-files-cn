package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.CloudArchiveRepository;

import cn.hutool.core.util.StrUtil;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class CloudArchiveRepositoryImpl extends AbstractSqlRepository implements CloudArchiveRepository {

    private final Clock clock;

    private ArchiveOperationsRepository operations;

    public CloudArchiveRepositoryImpl(SqlClient client, ArchiveOperationsRepository operations) {
        this(client);
        this.operations = operations;
    }

    public CloudArchiveRepositoryImpl(SqlClient sqlClient) {
        this(sqlClient, Clock.systemUTC());
    }

    CloudArchiveRepositoryImpl(SqlClient sqlClient, Clock clock) {
        super(sqlClient);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Future<Boolean> enqueue(long telegramId,
                                   long sourceChatId,
                                   long sourceTopicId,
                                   long sourceMessageId,
                                   long sourceAlbumId,
                                   long targetChatId,
                                   long targetTopicId,
                                   String fileUniqueId,
                                   String mode,
                                   String topicMode) {
        return insert(telegramId, sourceChatId, sourceTopicId, sourceMessageId,
                sourceAlbumId, targetChatId, targetTopicId, fileUniqueId, mode, topicMode,
                null, "PENDING");
    }

    @Override
    public Future<Boolean> stage(long telegramId,
                                 long sourceChatId,
                                 long sourceTopicId,
                                 long sourceMessageId,
                                 long sourceAlbumId,
                                 long targetChatId,
                                 long targetTopicId,
                                 String fileUniqueId,
                                 String mode,
                                 String topicMode,
                                 String historyJobId) {
        return insert(telegramId, sourceChatId, sourceTopicId, sourceMessageId,
                sourceAlbumId, targetChatId, targetTopicId, fileUniqueId, mode, topicMode,
                historyJobId, "STAGED");
    }

    private Future<Boolean> insert(long telegramId,
                                   long sourceChatId,
                                   long sourceTopicId,
                                   long sourceMessageId,
                                   long sourceAlbumId,
                                   long targetChatId,
                                   long targetTopicId,
                                   String fileUniqueId,
                                   String mode,
                                   String topicMode,
                                   String historyJobId,
                                   String status) {
        return findExisting(telegramId, sourceChatId, sourceMessageId, targetChatId)
                .compose(existing -> existing || operations == null ? Future.succeededFuture(existing)
                        : operations.wasDelivered(telegramId, sourceChatId, sourceMessageId, targetChatId))
                .compose(existing -> {
                    if (existing) {
                        return Future.succeededFuture(false);
                    }
                    long now = clock.millis();
                    String cancelledJob = historyJobId == null || historyJobId.startsWith("sync:") ? null
                            : historyJobId.startsWith("live:") ? historyJobId.substring(5) : historyJobId;
                    String guard = cancelledJob == null ? "" : " WHERE NOT EXISTS (SELECT 1 FROM telegram_archive_history_job WHERE id = ? AND completion_reason = 'RULE_DELETED')";
                    Tuple parameters = Tuple.of(
                                    UUID.randomUUID().toString(), telegramId, sourceChatId, sourceMessageId,
                                    sourceTopicId, sourceAlbumId, targetChatId, targetTopicId, fileUniqueId,
                                    mode, topicMode, historyJobId, sourceMessageId, status, now, now, now);
                    if (cancelledJob != null) parameters.addString(cancelledJob);
                    return preparedQuery("""
                                    INSERT INTO telegram_archive_record
                                        (id, telegram_id, source_chat_id, source_message_id,
                                         source_topic_id, source_album_id, target_chat_id, target_topic_id, target_message_id,
                                         file_unique_id, mode, topic_mode, history_job_id, delivery_sequence,
                                         status, attempt_count,
                                         next_attempt_at, last_error_code, last_error_message,
                                         created_at, updated_at)
                                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, ?, ?
                                    """ + guard)
                            .execute(parameters)
                            .map(rows -> rows.rowCount() > 0)
                            .recover(failure -> findExisting(
                                            telegramId, sourceChatId, sourceMessageId, targetChatId
                                    ).compose(duplicate -> duplicate
                                            ? Future.succeededFuture(false)
                                            : Future.failedFuture(failure)));
                });
    }

    @Override
    public Future<Void> updateTopics(String id,
                                     long sourceTopicId,
                                     long targetTopicId,
                                     String topicMode) {
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET source_topic_id = ?, target_topic_id = ?, topic_mode = ?, updated_at = ?
                        WHERE id = ? AND status = 'SENDING'
                        """)
                .execute(Tuple.of(sourceTopicId, targetTopicId, topicMode, clock.millis(), id))
                .mapEmpty();
    }

    @Override
    public Future<Void> releaseHistory(String historyJobId) {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'PENDING', next_attempt_at = ?, updated_at = ?
                        WHERE history_job_id = ? AND status = 'STAGED'
                        """)
                .execute(Tuple.of(now, now, historyJobId))
                .mapEmpty();
    }

    @Override
    public Future<Void> releaseHistoryThrough(String historyJobId, long sourceMessageId) {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'PENDING', next_attempt_at = ?, updated_at = ?
                        WHERE history_job_id = ? AND status = 'STAGED' AND source_message_id <= ?
                        """)
                .execute(Tuple.of(now, now, historyJobId, sourceMessageId)).mapEmpty();
    }

    @Override
    public Future<Void> holdHistoryForSync(String historyJobId, String syncKey) {
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET history_job_id = ?, status = 'STAGED', updated_at = ?
                        WHERE history_job_id = ? AND status IN ('STAGED', 'PENDING', 'RETRY')
                        """)
                .execute(Tuple.of(syncKey, clock.millis(), historyJobId)).mapEmpty();
    }

    @Override
    public Future<Long> initialSyncBaseline(long telegramId, long sourceChatId, long sourceTopicId,
                                            long targetChatId, long headMessageId) {
        String topicClause = sourceTopicId == 0 ? "" : " AND source_topic_id = ?";
        Tuple parameters = sourceTopicId == 0
                ? Tuple.of(telegramId, sourceChatId, targetChatId)
                : Tuple.of(telegramId, sourceChatId, targetChatId, sourceTopicId);
        return preparedQuery("""
                        SELECT MAX(CASE WHEN status <> 'STAGED' THEN source_message_id END) AS recorded,
                               MIN(CASE WHEN status = 'STAGED' THEN source_message_id END) AS held
                        FROM telegram_archive_record
                        WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                        """ + topicClause)
                .execute(parameters)
                .map(rows -> {
                    Row row = rows.iterator().next();
                    long recorded = value(row, "recorded");
                    long held = value(row, "held");
                    // An arriving held message is not evidence that preceding source history
                    // has been scanned. Start before it instead of jumping to the newest row.
                    if (held > 0) return recorded > 0 ? Math.min(recorded, held - 1) : held - 1;
                    return recorded > 0 ? recorded : headMessageId;
                });
    }

    @Override
    public Future<Void> releaseCompletedHistory() {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'PENDING', next_attempt_at = ?, updated_at = ?
                        WHERE status = 'STAGED' AND (
                            history_job_id IN (
                                SELECT id FROM telegram_archive_history_job
                                WHERE status = 'COMPLETED'
                            ) OR (
                                history_job_id LIKE 'live:%'
                                AND REPLACE(history_job_id, 'live:', '') IN (
                                    SELECT id FROM telegram_archive_history_job
                                    WHERE status IN ('PAUSED', 'COMPLETED', 'CANCELLED')
                                )
                            )
                        )
                        """)
                .execute(Tuple.of(now, now))
                .mapEmpty();
    }

    @Override
    public Future<Long> countOutstandingHistory(String historyJobId) {
        return preparedQuery("""
                        SELECT COUNT(*) AS total FROM telegram_archive_record
                        WHERE history_job_id = ?
                          AND status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING', 'UNKNOWN', 'FAILED')
                        """)
                .execute(Tuple.of(historyJobId))
                .map(rows -> value(rows.iterator().next(), "total"));
    }

    @Override
    public Future<Void> cancelHistory(String historyJobId) {
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'SKIPPED', last_error_code = 'HISTORY_CANCELLED',
                            last_error_message = 'History task cancelled; message was not delivered', updated_at = ?
                        WHERE history_job_id = ? AND status = 'FAILED'
                        """)
                .execute(Tuple.of(clock.millis(), historyJobId))
                .compose(_ -> preparedQuery("""
                        DELETE FROM telegram_archive_record
                        WHERE history_job_id = ?
                          AND status IN ('STAGED', 'PENDING', 'RETRY')
                        """).execute(Tuple.of(historyJobId)))
                .mapEmpty();
    }

    @Override
    public Future<Void> cancelRoute(long telegramId, long sourceChatId, long targetChatId) {
        return preparedQuery("""
                UPDATE telegram_archive_history_job SET status = 'CANCELLED',
                    completion_reason = 'RULE_DELETED', updated_at = ?
                WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                    AND status IN ('PENDING', 'RUNNING', 'DRAINING', 'PAUSED', 'FAILED')
                """).execute(Tuple.of(clock.millis(), telegramId, sourceChatId, targetChatId))
                .compose(_ -> preparedQuery("""
                    UPDATE telegram_archive_record SET status = 'CANCELLED',
                        last_error_code = 'RULE_DELETED', last_error_message = 'Archive rule deleted', updated_at = ?
                    WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                        AND status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING', 'FAILED', 'SKIPPED')
                    """).execute(Tuple.of(clock.millis(), telegramId, sourceChatId, targetChatId))).mapEmpty();
    }

    @Override
    public Future<JsonObject> routeStatistics(long telegramId, long sourceChatId, long targetChatId) {
        return preparedQuery("""
                SELECT COUNT(CASE WHEN status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING') THEN 1 END) AS pending,
                    COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed,
                    COUNT(CASE WHEN status = 'UNKNOWN' THEN 1 END) AS unconfirmed,
                    COUNT(CASE WHEN status IN ('PENDING', 'RETRY') AND next_attempt_at <= ? THEN 1 END) AS due,
                    MIN(CASE WHEN status IN ('PENDING', 'RETRY') AND next_attempt_at <= ? THEN next_attempt_at END) AS oldest_due_at,
                    MAX(CASE WHEN status = 'COMPLETED' THEN updated_at END) AS last_success_at,
                    MIN(CASE WHEN status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING') THEN created_at END) AS oldest_pending_at
                FROM telegram_archive_record WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                """).execute(Tuple.of(clock.millis(), clock.millis(), telegramId, sourceChatId, targetChatId))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new JsonObject().put("pending", value(row, "pending")).put("failed", value(row, "failed"))
                            .put("unconfirmed", value(row, "unconfirmed")).put("due", value(row, "due"))
                            .put("oldestDueAt", value(row, "oldest_due_at"))
                            .put("lastSuccessAt", value(row, "last_success_at")).put("oldestPendingAt", value(row, "oldest_pending_at"));
                }).compose(stats -> preparedQuery("""
                    SELECT id, status, updated_at, next_attempt_at, last_error_code FROM telegram_archive_record
                    WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                        AND status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING', 'FAILED', 'UNKNOWN')
                    ORDER BY source_message_id LIMIT 1
                    """).execute(Tuple.of(telegramId, sourceChatId, targetChatId)).map(rows -> {
                        if (rows.iterator().hasNext()) {
                            Row row = rows.iterator().next();
                            stats.put("firstStatus", row.getString("status")).put("nextAttemptAt", value(row, "next_attempt_at"))
                                    .put("errorCode", row.getString("last_error_code"))
                                    .put("firstRecordId", row.getString("id")).put("firstUpdatedAt", value(row, "updated_at"));
                        }
                        return stats;
                    })).compose(stats -> preparedQuery("""
                        SELECT COUNT(*) AS total FROM telegram_archive_history_job
                        WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                            AND status IN ('PENDING', 'RUNNING', 'DRAINING', 'PAUSED', 'FAILED')
                        """).execute(Tuple.of(telegramId, sourceChatId, targetChatId))
                        .map(rows -> stats.put("historyTasks", value(rows.iterator().next(), "total"))));
    }

    private Future<Boolean> findExisting(long telegramId,
                                         long sourceChatId,
                                         long sourceMessageId,
                                         long targetChatId) {
        return preparedQuery("""
                        SELECT id FROM telegram_archive_record
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_message_id = ? AND target_chat_id = ?
                        """)
                .execute(Tuple.of(telegramId, sourceChatId, sourceMessageId, targetChatId))
                .map(rows -> rows.iterator().hasNext());
    }

    @Override
    public Future<Boolean> claim(String id) {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'SENDING', attempt_count = attempt_count + 1,
                            last_error_code = NULL, last_error_message = NULL, updated_at = ?
                        WHERE id = ? AND status IN ('PENDING', 'RETRY')
                        """)
                .execute(Tuple.of(now, id))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Void> complete(String id, long targetMessageId) {
        return updateTerminal(id, "COMPLETED", targetMessageId, null, null)
                .compose(_ -> operations == null ? Future.succeededFuture()
                        : operations.captureSuccess(id, targetMessageId, clock.millis()));
    }

    @Override
    public Future<Void> skip(String id, String errorCode, String message) {
        return updateTerminal(id, "SKIPPED", null, errorCode, message);
    }

    private Future<Void> updateTerminal(String id,
                                        String status,
                                        Long targetMessageId,
                                        String errorCode,
                                        String message) {
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = ?, target_message_id = ?, last_error_code = ?,
                            last_error_message = ?, updated_at = ?
                        WHERE id = ? AND (status <> 'CANCELLED' OR ? IN ('COMPLETED', 'UNKNOWN'))
                            AND (status <> 'COMPLETED' OR ? = 'COMPLETED')
                        """)
                .execute(Tuple.of(status, targetMessageId, errorCode, truncate(message), clock.millis(), id, status, status))
                .mapEmpty();
    }

    @Override
    public Future<Void> fail(String id,
                             boolean retryable,
                             long nextAttemptAt,
                             String errorCode,
                             String message) {
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = ?, next_attempt_at = ?, last_error_code = ?,
                            last_error_message = ?, updated_at = ?
                        WHERE id = ? AND status NOT IN ('CANCELLED', 'COMPLETED')
                        """)
                .execute(Tuple.of(
                        retryable ? "RETRY" : "FAILED",
                        nextAttemptAt,
                        errorCode,
                        truncate(message),
                        clock.millis(),
                        id
                ))
                .mapEmpty();
    }

    @Override
    public Future<Void> recoverSendingAsUnknown() {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'UNKNOWN', last_error_code = 'PROCESS_RESTARTED',
                            last_error_message = 'Delivery may have completed before the process stopped; review before retrying.',
                            updated_at = ?
                        WHERE status = 'SENDING'
                        """)
                .execute(Tuple.of(now))
                .mapEmpty();
    }

    @Override
    public Future<List<CloudArchiveRecord>> listDue(long now, int limit) {
        return listDue(now, limit, List.of());
    }

    @Override
    public Future<List<CloudArchiveRecord>> listDue(long now, int limit, List<RouteOrder> routeOrders) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(now);
        String strictRoutes = routePredicate(routeOrders.stream().filter(RouteOrder::strictOrder).toList(), parameters);
        String heldRoutes = routePredicate(routeOrders.stream().filter(RouteOrder::holdAll).toList(), parameters);
        parameters.add(now);
        parameters.add(Math.max(1, Math.min(limit, 200)));
        // Apply durable order barriers before LIMIT so a blocked live route cannot consume
        // every scan slot and starve an unrelated runnable recovery or history route.
        return preparedQuery("""
                        SELECT * FROM (
                            SELECT archive_record.*,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY telegram_id, source_chat_id, target_chat_id
                                       ORDER BY source_message_id ASC, created_at ASC, id ASC
                                   ) AS route_position,
                                   FIRST_VALUE(CASE WHEN history_job_id IS NULL THEN 0
                                                    WHEN history_job_id LIKE 'sync:%%' THEN 1
                                                    ELSE 2 END) OVER (
                                       PARTITION BY telegram_id, source_chat_id, target_chat_id
                                       ORDER BY source_message_id ASC, created_at ASC, id ASC
                                   ) AS route_priority,
                                   MIN(CASE WHEN status IN ('SENDING', 'UNKNOWN')
                                                  OR (status IN ('PENDING', 'RETRY') AND next_attempt_at > ?)
                                                  OR (status IN ('STAGED', 'FAILED') AND %s)
                                            THEN source_message_id END) OVER (
                                       PARTITION BY telegram_id, source_chat_id, target_chat_id
                                   ) AS blocked_sequence
                            FROM telegram_archive_record archive_record
                            WHERE status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING', 'UNKNOWN', 'FAILED')
                              AND NOT (%s)
                        ) due_records
                        WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
                          AND (blocked_sequence IS NULL OR source_message_id < blocked_sequence)
                        ORDER BY route_priority ASC,
                                 route_position ASC, telegram_id ASC, source_chat_id ASC,
                                 target_chat_id ASC, source_message_id ASC, created_at ASC, id ASC
                        LIMIT ?
                        """.formatted(strictRoutes, heldRoutes))
                .execute(Tuple.from(parameters))
                .map(this::records);
    }

    private static String routePredicate(List<RouteOrder> routes, List<Object> parameters) {
        if (routes.isEmpty()) return "1 = 0";
        List<String> conditions = new ArrayList<>();
        for (RouteOrder route : routes) {
            conditions.add("(telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?)");
            parameters.add(route.telegramId());
            parameters.add(route.sourceChatId());
            parameters.add(route.targetChatId());
        }
        return "(" + String.join(" OR ", conditions) + ")";
    }

    @Override
    public Future<List<CloudArchiveRecord>> listUnsentAlbum(CloudArchiveRecord record) {
        return listUnsentAlbum(record, true);
    }

    @Override
    public Future<List<CloudArchiveRecord>> listUnsentAlbum(CloudArchiveRecord record, boolean strictOrder) {
        String statuses = strictOrder
                ? "'STAGED', 'PENDING', 'RETRY', 'SENDING', 'UNKNOWN', 'FAILED'"
                : "'PENDING', 'RETRY', 'SENDING', 'UNKNOWN'";
        return preparedQuery("""
                        SELECT * FROM telegram_archive_record
                        WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                          AND source_album_id = ?
                          AND status IN (%s)
                        ORDER BY source_message_id ASC, created_at ASC, id ASC
                        """.formatted(statuses))
                .execute(Tuple.of(record.telegramId(), record.sourceChatId(),
                        record.targetChatId(), record.sourceAlbumId()))
                .map(this::records);
    }

    @Override
    public Future<Boolean> isBatchReady(List<CloudArchiveRecord> records, long now, boolean strictOrder) {
        if (records.isEmpty()) return Future.succeededFuture(false);
        CloudArchiveRecord first = records.getFirst();
        String placeholders = String.join(",", java.util.Collections.nCopies(records.size(), "?"));
        List<Object> readyParameters = new ArrayList<>();
        readyParameters.add(now);
        records.forEach(record -> readyParameters.add(record.id()));
        List<Object> predecessorParameters = new ArrayList<>(List.of(
                first.telegramId(), first.sourceChatId(), first.targetChatId(),
                records.stream().mapToLong(CloudArchiveRecord::sourceMessageId).max().orElseThrow()));
        records.forEach(record -> predecessorParameters.add(record.id()));
        String statuses = strictOrder
                ? "'STAGED', 'PENDING', 'RETRY', 'SENDING', 'UNKNOWN', 'FAILED'"
                : "'PENDING', 'RETRY', 'SENDING', 'UNKNOWN'";
        return Future.all(
                        preparedQuery("SELECT COUNT(*) AS total FROM telegram_archive_record "
                                + "WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ? "
                                + "AND id IN (" + placeholders + ")")
                                .execute(Tuple.from(readyParameters)),
                        preparedQuery("SELECT COUNT(*) AS total FROM telegram_archive_record "
                                + "WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ? "
                                + "AND source_message_id <= ? AND status IN (" + statuses + ") "
                                + "AND id NOT IN (" + placeholders + ")")
                                .execute(Tuple.from(predecessorParameters)))
                .map(result -> {
                    RowSet<Row> ready = result.resultAt(0);
                    RowSet<Row> predecessors = result.resultAt(1);
                    return value(ready.iterator().next(), "total") == records.size()
                            && value(predecessors.iterator().next(), "total") == 0;
                });
    }

    @Override
    public Future<Void> unknown(String id, String errorCode, String message) {
        return updateTerminal(id, "UNKNOWN", null, errorCode, message);
    }

    @Override
    public Future<List<CloudArchiveRecord>> listRecent(int limit) {
        return listRecent(limit, null);
    }

    @Override
    public Future<List<CloudArchiveRecord>> listRecent(int limit, String status) {
        return listRecent(limit, status, 0, null, null);
    }

    @Override
    public Future<List<CloudArchiveRecord>> listRecent(int limit, String status, long telegramId, Long sourceChatId, Long targetChatId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM telegram_archive_record WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (StrUtil.isNotBlank(status) && !"ALL".equalsIgnoreCase(status)) {
            String upper = status.trim().toUpperCase(Locale.ROOT);
            switch (upper) {
                case "FAILED" -> sql.append(" AND status = 'FAILED'");
                case "PENDING" -> sql.append(" AND status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING')");
                default -> {
                    sql.append(" AND status = ?");
                    params.add(upper);
                }
            }
        }
        appendRouteScope(sql, params, telegramId, sourceChatId, targetChatId);
        if (operations != null) sql.append(" AND NOT EXISTS (SELECT 1 FROM archive_operator_audit a WHERE a.record_id = telegram_archive_record.id AND a.action = 'HIDE_LOG')");
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 500)));
        return preparedQuery(sql.toString())
                .execute(Tuple.from(params))
                .map(this::records);
    }

    @Override
    public Future<Long> maxSourceMessageId(long telegramId,
                                           long sourceChatId,
                                           long sourceTopicId,
                                           long targetChatId) {
        String topicClause = sourceTopicId == 0 ? "" : " AND source_topic_id = ?";
        Tuple parameters = sourceTopicId == 0
                ? Tuple.of(telegramId, sourceChatId, targetChatId)
                : Tuple.of(telegramId, sourceChatId, targetChatId, sourceTopicId);
        return preparedQuery("""
                        SELECT MAX(source_message_id) AS maximum
                        FROM telegram_archive_record
                        WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                        """ + topicClause)
                .execute(parameters)
                .map(rows -> value(rows.iterator().next(), "maximum"));
    }

    @Override
    public Future<Long> countCompletedSince(long telegramId, long since) {
        return preparedQuery("""
                        SELECT COUNT(*) AS total FROM telegram_archive_record
                        WHERE telegram_id = ? AND status = 'COMPLETED' AND updated_at >= ?
                        """)
                .execute(Tuple.of(telegramId, since))
                .map(rows -> value(rows.iterator().next(), "total"));
    }

    @Override
    public Future<Long> cooldownUntil(long telegramId, long now) {
        return preparedQuery("""
                        SELECT MAX(next_attempt_at) AS cooldown_until
                        FROM telegram_archive_record
                        WHERE telegram_id = ? AND next_attempt_at > ?
                          AND ((status = 'RETRY' AND last_error_code = 'TELEGRAM_WAIT')
                               OR (status = 'FAILED' AND last_error_code = 'PARTIAL_ALBUM_DELIVERY'))
                        """)
                .execute(Tuple.of(telegramId, now))
                .map(rows -> value(rows.iterator().next(), "cooldown_until"));
    }

    @Override
    public Future<Void> defer(String id, long nextAttemptAt, String code, String message) {
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'RETRY', next_attempt_at = ?, last_error_code = ?,
                            last_error_message = ?, updated_at = ?
                        WHERE id = ? AND status IN ('PENDING', 'RETRY')
                        """)
                .execute(Tuple.of(nextAttemptAt, code, truncate(message), clock.millis(), id))
                .mapEmpty();
    }

    @Override
    public Future<JsonObject> statistics() {
        return sqlClient.query("""
                        SELECT COUNT(*) AS total,
                               SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed,
                               SUM(CASE WHEN status = 'SKIPPED' THEN 1 ELSE 0 END) AS skipped,
                               SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                               SUM(CASE WHEN status = 'UNKNOWN' THEN 1 ELSE 0 END) AS unconfirmed,
                               SUM(CASE WHEN status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING') THEN 1 ELSE 0 END) AS pending
                        FROM telegram_archive_record
                        """)
                .execute()
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new JsonObject()
                            .put("total", value(row, "total"))
                            .put("completed", value(row, "completed"))
                            .put("skipped", value(row, "skipped"))
                            .put("failed", value(row, "failed"))
                            .put("pending", value(row, "pending"))
                            .put("unconfirmed", value(row, "unconfirmed"));
                });
    }

    @Override
    public Future<Boolean> retry(String id) {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_record
                        SET status = 'RETRY', next_attempt_at = ?,
                            last_error_code = NULL, last_error_message = NULL, updated_at = ?
                        WHERE id = ? AND status IN ('FAILED', 'UNKNOWN', 'SKIPPED')
                        """)
                .execute(Tuple.of(now, now, id))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Integer> retryAll(long telegramId, Long chatId) {
        return retryAll(telegramId, chatId, null);
    }

    @Override
    public Future<Integer> retryAll(long telegramId, Long chatId, Long targetChatId) {
        long now = clock.millis();
        StringBuilder sql = new StringBuilder("""
                UPDATE telegram_archive_record
                SET status = 'RETRY', next_attempt_at = ?,
                    last_error_code = NULL, last_error_message = NULL, updated_at = ?
                WHERE status IN ('FAILED', 'SKIPPED')
                """);
        List<Object> params = new ArrayList<>();
        params.add(now);
        params.add(now);
        appendRouteScope(sql, params, telegramId, chatId, targetChatId);
        return preparedQuery(sql.toString())
                .execute(Tuple.from(params))
                .map(RowSet::rowCount);
    }

    @Override
    public Future<Integer> clearRecords(long telegramId, String status) {
        return clearRecords(telegramId, status, null, null);
    }

    @Override
    public Future<Integer> clearRecords(long telegramId, String status, Long sourceChatId, Long targetChatId) {
        StringBuilder sql = new StringBuilder("DELETE FROM telegram_archive_record WHERE status IN ('COMPLETED', 'SKIPPED', 'CANCELLED')");
        List<Object> params = new ArrayList<>();
        appendRouteScope(sql, params, telegramId, sourceChatId, targetChatId);
        if (StrUtil.isNotBlank(status) && !"ALL".equalsIgnoreCase(status)) {
            sql.append(" AND status = ?");
            params.add(status.toUpperCase(Locale.ROOT));
        }
        if (operations != null) return operations.hideRecords(sql.toString().replace("DELETE FROM", "SELECT id FROM"), Tuple.from(params));
        return preparedQuery(sql.toString())
                .execute(Tuple.from(params))
                .map(RowSet::rowCount);
    }

    private static void appendRouteScope(StringBuilder sql, List<Object> params, long account, Long source, Long target) {
        if (account != 0) { sql.append(" AND telegram_id = ?"); params.add(account); }
        if (source != null && source != 0) { sql.append(" AND source_chat_id = ?"); params.add(source); }
        if (target != null && target != 0) { sql.append(" AND target_chat_id = ?"); params.add(target); }
    }

    private List<CloudArchiveRecord> records(RowSet<Row> rows) {
        List<CloudArchiveRecord> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(CloudArchiveRecord.from(row));
        }
        return result;
    }

    private static long value(Row row, String column) {
        Number value = (Number) row.getValue(column);
        return value == null ? 0L : value.longValue();
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
