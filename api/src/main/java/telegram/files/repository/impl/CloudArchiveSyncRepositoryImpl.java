package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.CloudArchiveSyncRepository;
import telegram.files.repository.CloudArchiveSyncState;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CloudArchiveSyncRepositoryImpl extends AbstractSqlRepository
        implements CloudArchiveSyncRepository {

    private final Clock clock;

    public CloudArchiveSyncRepositoryImpl(SqlClient sqlClient) {
        this(sqlClient, Clock.systemUTC());
    }

    CloudArchiveSyncRepositoryImpl(SqlClient sqlClient, Clock clock) {
        super(sqlClient);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Future<CloudArchiveSyncState> find(long telegramId, long sourceChatId,
                                              long sourceTopicId, long targetChatId) {
        return preparedQuery("""
                        SELECT * FROM telegram_archive_sync_state
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_topic_id = ? AND target_chat_id = ?
                        """)
                .execute(Tuple.of(telegramId, sourceChatId, sourceTopicId, targetChatId))
                .map(rows -> rows.iterator().hasNext()
                        ? CloudArchiveSyncState.from(rows.iterator().next()) : null);
    }

    @Override
    public Future<List<CloudArchiveSyncState>> listRoute(long telegramId, long sourceChatId,
                                                         long targetChatId) {
        return preparedQuery("""
                        SELECT * FROM telegram_archive_sync_state
                        WHERE telegram_id = ? AND source_chat_id = ? AND target_chat_id = ?
                        ORDER BY source_topic_id ASC
                        """)
                .execute(Tuple.of(telegramId, sourceChatId, targetChatId))
                .map(this::states);
    }

    @Override
    public Future<CloudArchiveSyncState> ensure(long telegramId, long sourceChatId,
                                                long sourceTopicId, long targetChatId,
                                                long baselineMessageId) {
        return find(telegramId, sourceChatId, sourceTopicId, targetChatId)
                .compose(existing -> {
                    if (existing != null) {
                        return Future.succeededFuture(existing);
                    }
                    long now = clock.millis();
                    return preparedQuery("""
                                    INSERT INTO telegram_archive_sync_state
                                        (telegram_id, source_chat_id, source_topic_id, target_chat_id,
                                         last_observed_message_id, recovery_target_message_id,
                                         recovery_cursor_message_id, status, scanned_count,
                                         matched_count, queued_count, last_error,
                                         last_reconciled_at, created_at, updated_at)
                                    VALUES (?, ?, ?, ?, ?, 0, 0, 'LIVE', 0, 0, 0, NULL, ?, ?, ?)
                                    """)
                            .execute(Tuple.of(telegramId, sourceChatId, sourceTopicId,
                                    targetChatId, baselineMessageId, now, now, now))
                            .compose(_ -> find(telegramId, sourceChatId, sourceTopicId, targetChatId))
                            .recover(_ -> find(telegramId, sourceChatId, sourceTopicId, targetChatId));
                });
    }

    @Override
    public Future<Void> beginRecovery(CloudArchiveSyncState state, long targetMessageId) {
        return preparedQuery("""
                        UPDATE telegram_archive_sync_state
                        SET recovery_target_message_id = ?, recovery_cursor_message_id = 0,
                            status = 'RECOVERING', scanned_count = 0, matched_count = 0,
                            queued_count = 0, last_error = NULL, updated_at = ?
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_topic_id = ? AND target_chat_id = ?
                        """)
                .execute(Tuple.of(targetMessageId, clock.millis(), state.telegramId(),
                        state.sourceChatId(), state.sourceTopicId(), state.targetChatId()))
                .mapEmpty();
    }

    @Override
    public Future<Void> advanceRecovery(CloudArchiveSyncState state, long cursorMessageId,
                                        int scanned, int matched, int queued) {
        return preparedQuery("""
                        UPDATE telegram_archive_sync_state
                        SET recovery_cursor_message_id = ?,
                            scanned_count = scanned_count + ?,
                            matched_count = matched_count + ?,
                            queued_count = queued_count + ?,
                            last_error = NULL, updated_at = ?
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_topic_id = ? AND target_chat_id = ?
                          AND status = 'RECOVERING'
                        """)
                .execute(Tuple.of(cursorMessageId, scanned, matched, queued, clock.millis(),
                        state.telegramId(), state.sourceChatId(), state.sourceTopicId(),
                        state.targetChatId()))
                .mapEmpty();
    }

    @Override
    public Future<Void> completeRecovery(CloudArchiveSyncState state) {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_sync_state
                        SET last_observed_message_id = recovery_target_message_id,
                            recovery_cursor_message_id = 0, status = 'LIVE',
                            last_error = NULL, last_reconciled_at = ?, updated_at = ?
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_topic_id = ? AND target_chat_id = ?
                          AND status = 'RECOVERING'
                        """)
                .execute(Tuple.of(now, now, state.telegramId(), state.sourceChatId(),
                        state.sourceTopicId(), state.targetChatId()))
                .mapEmpty();
    }

    @Override
    public Future<Void> touch(CloudArchiveSyncState state) {
        long now = clock.millis();
        return preparedQuery("""
                        UPDATE telegram_archive_sync_state
                        SET status = 'LIVE', last_error = NULL,
                            last_reconciled_at = ?, updated_at = ?
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_topic_id = ? AND target_chat_id = ?
                        """)
                .execute(Tuple.of(now, now, state.telegramId(), state.sourceChatId(),
                        state.sourceTopicId(), state.targetChatId()))
                .mapEmpty();
    }

    @Override
    public Future<Void> fail(CloudArchiveSyncState state, String message) {
        return preparedQuery("""
                        UPDATE telegram_archive_sync_state
                        SET status = 'ERROR', last_error = ?, updated_at = ?
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_topic_id = ? AND target_chat_id = ?
                        """)
                .execute(Tuple.of(truncate(message), clock.millis(), state.telegramId(),
                        state.sourceChatId(), state.sourceTopicId(), state.targetChatId()))
                .mapEmpty();
    }

    private List<CloudArchiveSyncState> states(RowSet<Row> rows) {
        List<CloudArchiveSyncState> result = new ArrayList<>();
        rows.forEach(row -> result.add(CloudArchiveSyncState.from(row)));
        return result;
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
