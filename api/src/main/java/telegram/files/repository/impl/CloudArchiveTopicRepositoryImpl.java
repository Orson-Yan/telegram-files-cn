package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.CloudArchiveTopicMap;
import telegram.files.repository.CloudArchiveTopicRepository;

import java.time.Clock;
import java.util.Objects;

public final class CloudArchiveTopicRepositoryImpl extends AbstractSqlRepository
        implements CloudArchiveTopicRepository {
    private final Clock clock;

    public CloudArchiveTopicRepositoryImpl(SqlClient sqlClient) {
        this(sqlClient, Clock.systemUTC());
    }

    CloudArchiveTopicRepositoryImpl(SqlClient sqlClient, Clock clock) {
        super(sqlClient);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Future<CloudArchiveTopicMap> find(long telegramId,
                                             long sourceChatId,
                                             long sourceTopicId,
                                             long targetChatId) {
        return preparedQuery("""
                        SELECT * FROM telegram_archive_topic_map
                        WHERE telegram_id = ? AND source_chat_id = ?
                          AND source_topic_id = ? AND target_chat_id = ?
                        """)
                .execute(Tuple.of(telegramId, sourceChatId, sourceTopicId, targetChatId))
                .map(rows -> rows.iterator().hasNext()
                        ? CloudArchiveTopicMap.from(rows.iterator().next()) : null);
    }

    @Override
    public Future<CloudArchiveTopicMap> save(long telegramId,
                                             long sourceChatId,
                                             long sourceTopicId,
                                             String sourceTopicName,
                                             long targetChatId,
                                             long targetTopicId,
                                             String targetTopicName,
                                             boolean general) {
        long now = clock.millis();
        return find(telegramId, sourceChatId, sourceTopicId, targetChatId)
                .compose(existing -> {
                    long createdAt = existing == null ? now : existing.createdAt();
                    CloudArchiveTopicMap value = new CloudArchiveTopicMap(
                            telegramId, sourceChatId, sourceTopicId, sourceTopicName,
                            targetChatId, targetTopicId, targetTopicName, general, createdAt, now);
                    if (existing == null) {
                        return preparedQuery("""
                                        INSERT INTO telegram_archive_topic_map
                                            (telegram_id, source_chat_id, source_topic_id,
                                             source_topic_name, target_chat_id, target_topic_id,
                                             target_topic_name, is_general, created_at, updated_at)
                                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                        """)
                                .execute(Tuple.of(telegramId, sourceChatId, sourceTopicId,
                                        sourceTopicName, targetChatId, targetTopicId,
                                        targetTopicName, general, createdAt, now))
                                .map(value);
                    }
                    return preparedQuery("""
                                    UPDATE telegram_archive_topic_map
                                    SET source_topic_name = ?, target_topic_id = ?,
                                        target_topic_name = ?, is_general = ?, updated_at = ?
                                    WHERE telegram_id = ? AND source_chat_id = ?
                                      AND source_topic_id = ? AND target_chat_id = ?
                                    """)
                            .execute(Tuple.of(sourceTopicName, targetTopicId, targetTopicName,
                                    general, now, telegramId, sourceChatId, sourceTopicId, targetChatId))
                            .map(value);
                });
    }
}
