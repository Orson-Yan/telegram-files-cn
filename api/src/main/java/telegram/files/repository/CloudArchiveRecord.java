package telegram.files.repository;

import io.vertx.sqlclient.Row;

/** Durable source-to-destination mapping for Telegram cloud archival. */
public record CloudArchiveRecord(
        String id,
        long telegramId,
        long sourceChatId,
        long sourceMessageId,
        long sourceAlbumId,
        long targetChatId,
        Long targetMessageId,
        String fileUniqueId,
        String mode,
        String status,
        int attemptCount,
        long nextAttemptAt,
        String lastErrorCode,
        String lastErrorMessage,
        long createdAt,
        long updatedAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS telegram_archive_record
            (
                id                 VARCHAR(64) PRIMARY KEY,
                telegram_id        BIGINT NOT NULL,
                source_chat_id     BIGINT NOT NULL,
                source_message_id  BIGINT NOT NULL,
                source_album_id    BIGINT NOT NULL DEFAULT 0,
                target_chat_id     BIGINT NOT NULL,
                target_message_id  BIGINT,
                file_unique_id     VARCHAR(255),
                mode               VARCHAR(32) NOT NULL,
                status             VARCHAR(32) NOT NULL,
                attempt_count      INT NOT NULL DEFAULT 0,
                next_attempt_at    BIGINT NOT NULL,
                last_error_code    VARCHAR(64),
                last_error_message VARCHAR(1024),
                created_at         BIGINT NOT NULL,
                updated_at         BIGINT NOT NULL,
                UNIQUE (telegram_id, source_chat_id, source_message_id, target_chat_id)
            )
            """;

    public static CloudArchiveRecord from(Row row) {
        return new CloudArchiveRecord(
                row.getString("id"),
                number(row, "telegram_id"),
                number(row, "source_chat_id"),
                number(row, "source_message_id"),
                number(row, "source_album_id"),
                number(row, "target_chat_id"),
                nullableNumber(row, "target_message_id"),
                row.getString("file_unique_id"),
                row.getString("mode"),
                row.getString("status"),
                (int) number(row, "attempt_count"),
                number(row, "next_attempt_at"),
                row.getString("last_error_code"),
                row.getString("last_error_message"),
                number(row, "created_at"),
                number(row, "updated_at")
        );
    }

    private static long number(Row row, String column) {
        Number value = (Number) row.getValue(column);
        return value == null ? 0L : value.longValue();
    }

    private static Long nullableNumber(Row row, String column) {
        Number value = (Number) row.getValue(column);
        return value == null ? null : value.longValue();
    }

    public static class CloudArchiveRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
