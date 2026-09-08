package telegram.files.repository;

import io.vertx.sqlclient.Row;

/** Persistent source-to-destination forum topic mapping for a cloud archive route. */
public record CloudArchiveTopicMap(
        long telegramId,
        long sourceChatId,
        long sourceTopicId,
        String sourceTopicName,
        long targetChatId,
        long targetTopicId,
        String targetTopicName,
        boolean general,
        long createdAt,
        long updatedAt
) {
    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS telegram_archive_topic_map
            (
                telegram_id       BIGINT NOT NULL,
                source_chat_id    BIGINT NOT NULL,
                source_topic_id   BIGINT NOT NULL,
                source_topic_name VARCHAR(255) NOT NULL,
                target_chat_id    BIGINT NOT NULL,
                target_topic_id   BIGINT NOT NULL,
                target_topic_name VARCHAR(255) NOT NULL,
                is_general        BOOLEAN NOT NULL DEFAULT FALSE,
                created_at        BIGINT NOT NULL,
                updated_at        BIGINT NOT NULL,
                PRIMARY KEY (telegram_id, source_chat_id, source_topic_id, target_chat_id)
            )
            """;

    public static CloudArchiveTopicMap from(Row row) {
        return new CloudArchiveTopicMap(
                number(row, "telegram_id"),
                number(row, "source_chat_id"),
                number(row, "source_topic_id"),
                row.getString("source_topic_name"),
                number(row, "target_chat_id"),
                number(row, "target_topic_id"),
                row.getString("target_topic_name"),
                bool(row, "is_general"),
                number(row, "created_at"),
                number(row, "updated_at")
        );
    }

    private static long number(Row row, String column) {
        Number value = (Number) row.getValue(column);
        return value == null ? 0L : value.longValue();
    }

    private static boolean bool(Row row, String column) {
        Object value = row.getValue(column);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static final class CloudArchiveTopicMapDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
