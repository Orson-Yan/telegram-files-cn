package telegram.files.repository;

import cn.hutool.core.lang.Version;
import cn.hutool.core.map.MapUtil;
import io.vertx.sqlclient.Row;

import java.util.TreeMap;

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
        String scanMode,
        String stage,
        int maxMessages,
        String topicIdsJson,
        int topicIndex,
        int topicCount,
        long currentTopicId,
        long fromMessageId,
        int scannedCount,
        int matchedCount,
        int queuedCount,
        String completionReason,
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
                scan_mode          VARCHAR(16) NOT NULL DEFAULT 'LIMIT',
                stage              VARCHAR(32) NOT NULL DEFAULT 'DISCOVERING',
                max_messages       INT NOT NULL,
                topic_ids_json     VARCHAR(32768),
                topic_index        INT NOT NULL DEFAULT 0,
                topic_count        INT NOT NULL DEFAULT 0,
                current_topic_id   BIGINT NOT NULL DEFAULT 0,
                from_message_id    BIGINT NOT NULL DEFAULT 0,
                scanned_count      INT NOT NULL DEFAULT 0,
                matched_count      INT NOT NULL DEFAULT 0,
                queued_count       INT NOT NULL DEFAULT 0,
                completion_reason  VARCHAR(32),
                last_error         VARCHAR(1024),
                created_at         BIGINT NOT NULL,
                updated_at         BIGINT NOT NULL
            )
            """;

    public static final TreeMap<Version, String[]> MIGRATIONS = new TreeMap<>(MapUtil.ofEntries(
            MapUtil.entry(new Version("0.7.0"), new String[]{
                    "ALTER TABLE telegram_archive_history_job ADD COLUMN scan_mode VARCHAR(16) NOT NULL DEFAULT 'LIMIT';",
                    "ALTER TABLE telegram_archive_history_job ADD COLUMN stage VARCHAR(32) NOT NULL DEFAULT 'DISCOVERING';",
                    "ALTER TABLE telegram_archive_history_job ADD COLUMN topic_ids_json VARCHAR(32768);",
                    "ALTER TABLE telegram_archive_history_job ADD COLUMN topic_index INT NOT NULL DEFAULT 0;",
                    "ALTER TABLE telegram_archive_history_job ADD COLUMN topic_count INT NOT NULL DEFAULT 0;",
                    "ALTER TABLE telegram_archive_history_job ADD COLUMN current_topic_id BIGINT NOT NULL DEFAULT 0;",
                    "ALTER TABLE telegram_archive_history_job ADD COLUMN completion_reason VARCHAR(32);",
                    "UPDATE telegram_archive_history_job SET status = 'PENDING', from_message_id = 0, scanned_count = 0, matched_count = 0, last_error = NULL WHERE status = 'FAILED' AND last_error LIKE '%from_message_id%';"
            })
    ));

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
                row.getString("scan_mode"),
                row.getString("stage"),
                (int) number(row, "max_messages"),
                row.getString("topic_ids_json"),
                (int) number(row, "topic_index"),
                (int) number(row, "topic_count"),
                number(row, "current_topic_id"),
                number(row, "from_message_id"),
                (int) number(row, "scanned_count"),
                (int) number(row, "matched_count"),
                (int) number(row, "queued_count"),
                row.getString("completion_reason"),
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

        @Override
        public TreeMap<Version, String[]> getMigrations() {
            return MIGRATIONS;
        }
    }
}
