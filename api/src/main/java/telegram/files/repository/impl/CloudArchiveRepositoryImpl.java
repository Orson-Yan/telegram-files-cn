package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.CloudArchiveRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CloudArchiveRepositoryImpl extends AbstractSqlRepository implements CloudArchiveRepository {

    private final Clock clock;

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
                                   String mode) {
        return insert(telegramId, sourceChatId, sourceTopicId, sourceMessageId,
                sourceAlbumId, targetChatId, targetTopicId, fileUniqueId, mode,
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
                                 String historyJobId) {
        return insert(telegramId, sourceChatId, sourceTopicId, sourceMessageId,
                sourceAlbumId, targetChatId, targetTopicId, fileUniqueId, mode,
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
                                   String historyJobId,
                                   String status) {
        return findExisting(telegramId, sourceChatId, sourceMessageId, targetChatId)
                .compose(existing -> {
                    if (existing) {
                        return Future.succeededFuture(false);
                    }
                    long now = clock.millis();
                    return preparedQuery("""
                                    INSERT INTO telegram_archive_record
                                        (id, telegram_id, source_chat_id, source_message_id,
                                         source_topic_id, source_album_id, target_chat_id, target_topic_id, target_message_id,
                                         file_unique_id, mode, history_job_id, delivery_sequence,
                                         status, attempt_count,
                                         next_attempt_at, last_error_code, last_error_message,
                                         created_at, updated_at)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, ?, ?)
                                    """)
                            .execute(Tuple.of(
                                    UUID.randomUUID().toString(),
                                    telegramId,
                                    sourceChatId,
                                    sourceMessageId,
                                    sourceTopicId,
                                    sourceAlbumId,
                                    targetChatId,
                                    targetTopicId,
                                    fileUniqueId,
                                    mode,
                                    historyJobId,
                                    sourceMessageId,
                                    status,
                                    now,
                                    now,
                                    now
                            ))
                            .map(true)
                            .recover(failure -> findExisting(
                                            telegramId, sourceChatId, sourceMessageId, targetChatId
                                    ).compose(duplicate -> duplicate
                                            ? Future.succeededFuture(false)
                                            : Future.failedFuture(failure)));
                });
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
    public Future<Long> countOutstandingHistory(String historyJobId) {
        return preparedQuery("""
                        SELECT COUNT(*) AS total FROM telegram_archive_record
                        WHERE history_job_id = ?
                          AND status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING')
                        """)
                .execute(Tuple.of(historyJobId))
                .map(rows -> value(rows.iterator().next(), "total"));
    }

    @Override
    public Future<Void> cancelHistory(String historyJobId) {
        return preparedQuery("""
                        DELETE FROM telegram_archive_record
                        WHERE history_job_id = ?
                          AND status IN ('STAGED', 'PENDING', 'RETRY')
                        """)
                .execute(Tuple.of(historyJobId))
                .mapEmpty();
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
        return updateTerminal(id, "COMPLETED", targetMessageId, null, null);
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
                        WHERE id = ?
                        """)
                .execute(Tuple.of(status, targetMessageId, errorCode, truncate(message), clock.millis(), id))
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
                        WHERE id = ?
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
        return preparedQuery("""
                        SELECT * FROM telegram_archive_record
                        WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
                        ORDER BY delivery_sequence ASC, created_at ASC
                        LIMIT ?
                        """)
                .execute(Tuple.of(now, Math.max(1, Math.min(limit, 200))))
                .map(this::records);
    }

    @Override
    public Future<List<CloudArchiveRecord>> listRecent(int limit) {
        return preparedQuery("""
                        SELECT * FROM telegram_archive_record
                        ORDER BY created_at DESC
                        LIMIT ?
                        """)
                .execute(Tuple.of(Math.max(1, Math.min(limit, 500))))
                .map(this::records);
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
    public Future<Long> countPending(long telegramId) {
        return preparedQuery("""
                        SELECT COUNT(*) AS total FROM telegram_archive_record
                        WHERE telegram_id = ? AND status IN ('PENDING', 'RETRY', 'SENDING')
                        """)
                .execute(Tuple.of(telegramId))
                .map(rows -> value(rows.iterator().next(), "total"));
    }

    @Override
    public Future<Long> cooldownUntil(long telegramId, long now) {
        return preparedQuery("""
                        SELECT MAX(next_attempt_at) AS cooldown_until
                        FROM telegram_archive_record
                        WHERE telegram_id = ? AND status = 'RETRY'
                          AND last_error_code = 'TELEGRAM_WAIT' AND next_attempt_at > ?
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
                               SUM(CASE WHEN status IN ('FAILED', 'UNKNOWN') THEN 1 ELSE 0 END) AS failed,
                               SUM(CASE WHEN status IN ('PENDING', 'RETRY', 'SENDING') THEN 1 ELSE 0 END) AS pending
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
                            .put("pending", value(row, "pending"));
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
