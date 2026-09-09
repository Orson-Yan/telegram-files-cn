package telegram.files.repository;

import io.vertx.sqlclient.Row;

import java.util.TreeMap;

/** Durable verification cursor for one cloud-archive source topic. */
public record CloudArchiveSyncState(
        long telegramId,
        long sourceChatId,
        long sourceTopicId,
        long targetChatId,
        long lastObservedMessageId,
        long recoveryTargetMessageId,
        long recoveryCursorMessageId,
        String status,
        int scannedCount,
        int matchedCount,
        int queuedCount,
        String lastError,
        long lastReconciledAt,
        long createdAt,
        long updatedAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS telegram_archive_sync_state
            (
                telegram_id               BIGINT NOT NULL,
                source_chat_id            BIGINT NOT NULL,
                source_topic_id           BIGINT NOT NULL DEFAULT 0,
                target_chat_id            BIGINT NOT NULL,
                last_observed_message_id  BIGINT NOT NULL DEFAULT 0,
                recovery_target_message_id BIGINT NOT NULL DEFAULT 0,
                recovery_cursor_message_id BIGINT NOT NULL DEFAULT 0,
                status                    VARCHAR(32) NOT NULL DEFAULT 'LIVE',
                scanned_count             INT NOT NULL DEFAULT 0,
                matched_count             INT NOT NULL DEFAULT 0,
                queued_count              INT NOT NULL DEFAULT 0,
                last_error                VARCHAR(1024),
                last_reconciled_at         BIGINT NOT NULL DEFAULT 0,
                created_at                 BIGINT NOT NULL,
                updated_at                 BIGINT NOT NULL,
                PRIMARY KEY (telegram_id, source_chat_id, source_topic_id, target_chat_id)
            )
            """;

    public static CloudArchiveSyncState from(Row row) {
        return new CloudArchiveSyncState(
                number(row, "telegram_id"),
                number(row, "source_chat_id"),
                number(row, "source_topic_id"),
                number(row, "target_chat_id"),
                number(row, "last_observed_message_id"),
                number(row, "recovery_target_message_id"),
                number(row, "recovery_cursor_message_id"),
                row.getString("status"),
                (int) number(row, "scanned_count"),
                (int) number(row, "matched_count"),
                (int) number(row, "queued_count"),
                row.getString("last_error"),
                number(row, "last_reconciled_at"),
                number(row, "created_at"),
                number(row, "updated_at")
        );
    }

    private static long number(Row row, String column) {
        Number value = (Number) row.getValue(column);
        return value == null ? 0L : value.longValue();
    }

    public static final class CloudArchiveSyncStateDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }

        @Override
        public TreeMap<cn.hutool.core.lang.Version, String[]> getMigrations() {
            return new TreeMap<>();
        }
    }
}
