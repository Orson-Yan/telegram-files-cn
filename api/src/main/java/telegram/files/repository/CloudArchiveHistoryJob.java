package telegram.files.repository;

import io.vertx.sqlclient.Row;

/** Durable, resumable scan of historical Telegram messages for a cloud archive rule. */
public record CloudArchiveHistoryJob(
        String id,
        long telegramId,
        long sourceChatId,
        long sourceTopicId,
        long targetChatId,
        long targetTopicId,
        String ruleJson,
        String status,
        int maxMessages,
        long fromMessageId,
        int scannedCount,
        int matchedCount,
        int queuedCount,
        String lastError,
        long createdAt,
        long updatedAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS telegram_archive_history_job
            (
                id                 VARCHAR(64) PRIMARY KEY,
                telegram_id        BIGINT NOT NULL,
                source_chat_id     BIGINT NOT NULL,
                source_topic_id    BIGINT NOT NULL DEFAULT 0,
                target_chat_id     BIGINT NOT NULL,
                target_topic_id    BIGINT NOT NULL DEFAULT 0,
                rule_json          VARCHAR(8192) NOT NULL,
                status             VARCHAR(32) NOT NULL,
                max_messages       INT NOT NULL,
                from_message_id    BIGINT NOT NULL DEFAULT 0,
                scanned_count      INT NOT NULL DEFAULT 0,
                matched_count      INT NOT NULL DEFAULT 0,
                queued_count       INT NOT NULL DEFAULT 0,
                last_error         VARCHAR(1024),
                created_at         BIGINT NOT NULL,
                updated_at         BIGINT NOT NULL
            )
            """;

    public static CloudArchiveHistoryJob from(Row row) {
        return new CloudArchiveHistoryJob(
                row.getString("id"),
                number(row, "telegram_id"),
                number(row, "source_chat_id"),
                number(row, "source_topic_id"),
                number(row, "target_chat_id"),
                number(row, "target_topic_id"),
                row.getString("rule_json"),
                row.getString("status"),
                (int) number(row, "max_messages"),
                number(row, "from_message_id"),
                (int) number(row, "scanned_count"),
                (int) number(row, "matched_count"),
                (int) number(row, "queued_count"),
                row.getString("last_error"),
                number(row, "created_at"),
                number(row, "updated_at")
        );
    }

    private static long number(Row row, String column) {
        Number value = (Number) row.getValue(column);
        return value == null ? 0L : value.longValue();
    }

    public static final class CloudArchiveHistoryJobDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
