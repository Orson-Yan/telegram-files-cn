package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.CloudArchiveHistoryJob;
import telegram.files.repository.CloudArchiveHistoryRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CloudArchiveHistoryRepositoryImpl extends AbstractSqlRepository
        implements CloudArchiveHistoryRepository {

    private final Clock clock;

    public CloudArchiveHistoryRepositoryImpl(SqlClient sqlClient) {
        this(sqlClient, Clock.systemUTC());
    }

    CloudArchiveHistoryRepositoryImpl(SqlClient sqlClient, Clock clock) {
        super(sqlClient);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Future<CloudArchiveHistoryJob> create(long telegramId,
                                                 long sourceChatId,
                                                 long sourceTopicId,
                                                 long targetChatId,
                                                 long targetTopicId,
                                                 String ruleJson,
                                                 int maxMessages) {
        return preparedQuery("""
                        SELECT id FROM telegram_archive_history_job
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND status IN ('PENDING', 'RUNNING', 'PAUSED')
                        LIMIT 1
                        """)
                .execute(Tuple.of(telegramId, sourceChatId))
                .compose(rows -> {
                    if (rows.iterator().hasNext()) {
                        return Future.failedFuture(new IllegalStateException(
                                "This source already has an active history task"));
                    }
                    long now = clock.millis();
                    String id = UUID.randomUUID().toString();
                    return preparedQuery("""
                                    INSERT INTO telegram_archive_history_job
                                        (id, telegram_id, source_chat_id, source_topic_id,
                                         target_chat_id, target_topic_id, rule_json, status,
                                         max_messages, from_message_id, scanned_count, matched_count,
                                         queued_count, last_error, created_at, updated_at)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 0, 0, 0, 0, NULL, ?, ?)
                                    """)
                            .execute(Tuple.of(id, telegramId, sourceChatId, sourceTopicId,
                                    targetChatId, targetTopicId, ruleJson, maxMessages, now, now))
                            .map(_ -> new CloudArchiveHistoryJob(id, telegramId, sourceChatId,
                                    sourceTopicId, targetChatId, targetTopicId, ruleJson, "PENDING",
                                    maxMessages, 0, 0, 0, 0, null, now, now));
                });
    }

    @Override
    public Future<List<CloudArchiveHistoryJob>> listRecent(int limit) {
        return preparedQuery("""
                        SELECT * FROM telegram_archive_history_job
                        ORDER BY created_at DESC LIMIT ?
                        """)
                .execute(Tuple.of(Math.max(1, Math.min(limit, 200))))
                .map(this::jobs);
    }

    @Override
    public Future<List<CloudArchiveHistoryJob>> listRunnable(int limit) {
        return preparedQuery("""
                        SELECT * FROM telegram_archive_history_job
                        WHERE status IN ('PENDING', 'RUNNING')
                        ORDER BY created_at ASC LIMIT ?
                        """)
                .execute(Tuple.of(Math.max(1, Math.min(limit, 20))))
                .map(this::jobs);
    }

    @Override
    public Future<Boolean> start(String id) {
        return preparedQuery("""
                        UPDATE telegram_archive_history_job
                        SET status = 'RUNNING', updated_at = ?
                        WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                        """)
                .execute(Tuple.of(clock.millis(), id))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Void> advance(String id,
                                long fromMessageId,
                                int scanned,
                                int matched,
                                int queued,
                                boolean completed) {
        return preparedQuery("""
                        UPDATE telegram_archive_history_job
                        SET status = ?, from_message_id = ?,
                            scanned_count = scanned_count + ?,
                            matched_count = matched_count + ?,
                            queued_count = queued_count + ?,
                            last_error = NULL, updated_at = ?
                        WHERE id = ? AND status = 'RUNNING'
                        """)
                .execute(Tuple.of(completed ? "COMPLETED" : "RUNNING", fromMessageId,
                        scanned, matched, queued, clock.millis(), id))
                .mapEmpty();
    }

    @Override
    public Future<Void> fail(String id, String message) {
        return preparedQuery("""
                        UPDATE telegram_archive_history_job
                        SET status = 'FAILED', last_error = ?, updated_at = ?
                        WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                        """)
                .execute(Tuple.of(truncate(message), clock.millis(), id))
                .mapEmpty();
    }

    @Override
    public Future<Boolean> transition(String id, String action) {
        String normalized = action == null ? "" : action.toLowerCase();
        String sql = switch (normalized) {
            case "pause" -> """
                    UPDATE telegram_archive_history_job SET status = 'PAUSED', updated_at = ?
                    WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                    """;
            case "resume" -> """
                    UPDATE telegram_archive_history_job
                    SET status = 'PENDING', last_error = NULL, updated_at = ?
                    WHERE id = ? AND status IN ('PAUSED', 'FAILED')
                    """;
            case "cancel" -> """
                    UPDATE telegram_archive_history_job SET status = 'CANCELLED', updated_at = ?
                    WHERE id = ? AND status IN ('PENDING', 'RUNNING', 'PAUSED')
                    """;
            default -> null;
        };
        if (sql == null) {
            return Future.failedFuture(new IllegalArgumentException("Unsupported history task action"));
        }
        return preparedQuery(sql)
                .execute(Tuple.of(clock.millis(), id))
                .map(rows -> rows.rowCount() == 1);
    }

    private List<CloudArchiveHistoryJob> jobs(RowSet<Row> rows) {
        List<CloudArchiveHistoryJob> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(CloudArchiveHistoryJob.from(row));
        }
        return result;
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
