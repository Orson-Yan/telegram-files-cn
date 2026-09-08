package telegram.files.repository;

import cn.hutool.core.lang.Version;
import cn.hutool.core.map.MapUtil;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;

import java.util.TreeMap;

/** Durable source-to-destination mapping for Telegram cloud archival. */
public record CloudArchiveRecord(
        String id,
        long telegramId,
        long sourceChatId,
        long sourceTopicId,
        long sourceMessageId,
        long sourceAlbumId,
        long targetChatId,
        long targetTopicId,
        Long targetMessageId,
        String fileUniqueId,
        String mode,
        String historyJobId,
        long deliverySequence,
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
                source_topic_id    BIGINT NOT NULL DEFAULT 0,
                source_message_id  BIGINT NOT NULL,
                source_album_id    BIGINT NOT NULL DEFAULT 0,
                target_chat_id     BIGINT NOT NULL,
                target_topic_id    BIGINT NOT NULL DEFAULT 0,
                target_message_id  BIGINT,
                file_unique_id     VARCHAR(255),
                mode               VARCHAR(32) NOT NULL,
                history_job_id     VARCHAR(64),
                delivery_sequence  BIGINT NOT NULL DEFAULT 0,
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

    public static final String RATE_INDEX = """
            CREATE INDEX idx_archive_account_status_time
            ON telegram_archive_record (telegram_id, status, updated_at)
            """;

    public static final String DUE_INDEX = """
            CREATE INDEX idx_archive_due_delivery
            ON telegram_archive_record (status, next_attempt_at, delivery_sequence)
            """;

    public static final String HISTORY_INDEX = """
            CREATE INDEX idx_archive_history_delivery
            ON telegram_archive_record (history_job_id, status, delivery_sequence)
            """;

    public static final TreeMap<Version, String[]> MIGRATIONS = new TreeMap<>(MapUtil.ofEntries(
            MapUtil.entry(new Version("0.6.0"), new String[]{
                    "ALTER TABLE telegram_archive_record ADD COLUMN source_topic_id BIGINT NOT NULL DEFAULT 0;",
                    "ALTER TABLE telegram_archive_record ADD COLUMN target_topic_id BIGINT NOT NULL DEFAULT 0;",
                    RATE_INDEX
            }),
            MapUtil.entry(new Version("0.7.0"), new String[]{
                    "ALTER TABLE telegram_archive_record ADD COLUMN history_job_id VARCHAR(64);",
                    "ALTER TABLE telegram_archive_record ADD COLUMN delivery_sequence BIGINT NOT NULL DEFAULT 0;"
            })
    ));

    public static CloudArchiveRecord from(Row row) {
        return new CloudArchiveRecord(
                row.getString("id"),
                number(row, "telegram_id"),
                number(row, "source_chat_id"),
                number(row, "source_topic_id"),
                number(row, "source_message_id"),
                number(row, "source_album_id"),
                number(row, "target_chat_id"),
                number(row, "target_topic_id"),
                nullableNumber(row, "target_message_id"),
                row.getString("file_unique_id"),
                row.getString("mode"),
                row.getString("history_job_id"),
                number(row, "delivery_sequence"),
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

        @Override
        public TreeMap<Version, String[]> getMigrations() {
            return MIGRATIONS;
        }

        @Override
        public Future<Void> createTable(SqlClient sqlClient) {
            return Definition.super.createTable(sqlClient)
                    .compose(_ -> createIndexes(sqlClient));
        }

        @Override
        public Future<Void> migrate(SqlClient sqlClient, Version lastVersion, Version currentVersion) {
            return Definition.super.migrate(sqlClient, lastVersion, currentVersion)
                    .compose(_ -> createIndexes(sqlClient));
        }

        private Future<Void> createIndexes(SqlClient sqlClient) {
            Future<Void> future = Future.succeededFuture();
            for (String index : new String[]{RATE_INDEX, DUE_INDEX, HISTORY_INDEX}) {
                future = future.compose(_ -> sqlClient.query(index).execute()
                        .recover(_ -> Future.succeededFuture()).mapEmpty());
            }
            return future;
        }
    }
}
