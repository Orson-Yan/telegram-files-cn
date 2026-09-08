package telegram.files.repository.impl;

import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import cn.hutool.core.lang.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.CloudArchiveHistoryJob;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CloudArchiveHistoryRepositoryImplTest {

    @Test
    void migrationAddsPaginationTopicAndCompletionColumns(Vertx vertx,
                                                           VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1));
        String oldScheme = """
                CREATE TABLE telegram_archive_history_job (
                    id VARCHAR(64) PRIMARY KEY, telegram_id BIGINT NOT NULL,
                    source_chat_id BIGINT NOT NULL, source_topic_id BIGINT NOT NULL DEFAULT 0,
                    target_chat_id BIGINT NOT NULL, target_topic_id BIGINT NOT NULL DEFAULT 0,
                    rule_json VARCHAR(8192) NOT NULL, status VARCHAR(32) NOT NULL,
                    max_messages INT NOT NULL, from_message_id BIGINT NOT NULL DEFAULT 0,
                    scanned_count INT NOT NULL DEFAULT 0, matched_count INT NOT NULL DEFAULT 0,
                    queued_count INT NOT NULL DEFAULT 0, last_error VARCHAR(1024),
                    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
                )
                """;

        pool.query(oldScheme).execute()
                .compose(_ -> pool.query("""
                        INSERT INTO telegram_archive_history_job
                            (id, telegram_id, source_chat_id, source_topic_id,
                             target_chat_id, target_topic_id, rule_json, status,
                             max_messages, from_message_id, scanned_count, matched_count,
                             queued_count, last_error, created_at, updated_at)
                        VALUES ('broken', 7, 100, 0, 200, 0, '{}', 'FAILED',
                                1000, 12345, 50, 20, 18,
                                'Invalid value of parameter from_message_id', 1, 1)
                        """).execute())
                .compose(_ -> new CloudArchiveHistoryJob.CloudArchiveHistoryJobDefinition().migrate(
                        pool, new Version("0.6.0"), new Version("0.7.0")))
                .compose(_ -> pool.query("PRAGMA table_info(telegram_archive_history_job)").execute())
                .compose(rows -> pool.query("""
                                SELECT status, from_message_id, scanned_count,
                                       matched_count, queued_count
                                FROM telegram_archive_history_job WHERE id = 'broken'
                                """).execute().map(result -> new Object[]{rows, result.iterator().next()}))
                .eventually(pool::close)
                .onComplete(context.succeeding(result -> context.verify(() -> {
                    @SuppressWarnings("unchecked")
                    var rows = (io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>) result[0];
                    var broken = (io.vertx.sqlclient.Row) result[1];
                    var columns = StreamSupport.stream(rows.spliterator(), false)
                            .map(row -> row.getString("name"))
                            .toList();
                    assertTrue(columns.contains("scan_mode"));
                    assertTrue(columns.contains("stage"));
                    assertTrue(columns.contains("topic_ids_json"));
                    assertTrue(columns.contains("current_topic_id"));
                    assertTrue(columns.contains("completion_reason"));
                    assertEquals("PENDING", broken.getString("status"));
                    assertEquals(0, ((Number) broken.getValue("from_message_id")).longValue());
                    assertEquals(0, ((Number) broken.getValue("scanned_count")).intValue());
                    assertEquals(0, ((Number) broken.getValue("matched_count")).intValue());
                    assertEquals(18, ((Number) broken.getValue("queued_count")).intValue());
                    context.completeNow();
                })));
    }

    @Test
    void persistsProgressAndSupportsPauseResumeAndCompletion(Vertx vertx,
                                                             VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1));
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        CloudArchiveHistoryRepositoryImpl repository =
                new CloudArchiveHistoryRepositoryImpl(pool, clock);

        pool.query(CloudArchiveHistoryJob.SCHEME).execute()
                .compose(_ -> repository.create(7, 100, 11, 200, 12, "{}", "ALL", 0))
                .compose(job -> repository.start(job.id()).map(job))
                .compose(job -> repository.initializeTopics(job.id(), "[11,22]", 2, 11).map(job))
                .compose(job -> repository.advance(job.id(), 900, 50, 20, 18).map(job))
                .compose(job -> repository.transition(job.id(), "pause").map(job))
                .compose(job -> repository.transition(job.id(), "resume").map(job))
                .compose(job -> repository.start(job.id()).map(job))
                .compose(job -> repository.completeTopic(job.id(), 1, 22, false, null).map(job))
                .compose(job -> repository.advance(job.id(), 800, 50, 10, 9).map(job))
                .compose(job -> repository.completeTopic(
                        job.id(), 2, 0, true, "HISTORY_END").map(job))
                .compose(job -> repository.completeDraining(job.id()))
                .compose(_ -> repository.listRecent(10))
                .eventually(pool::close)
                .onComplete(context.succeeding(jobs -> context.verify(() -> {
                    assertEquals(1, jobs.size());
                    CloudArchiveHistoryJob job = jobs.getFirst();
                    assertEquals("COMPLETED", job.status());
                    assertEquals("COMPLETED", job.stage());
                    assertEquals("ALL", job.scanMode());
                    assertEquals(2, job.topicCount());
                    assertEquals(2, job.topicIndex());
                    assertEquals("HISTORY_END", job.completionReason());
                    assertEquals(100, job.scannedCount());
                    assertEquals(30, job.matchedCount());
                    assertEquals(27, job.queuedCount());
                    assertEquals(0, job.fromMessageId());
                    assertTrue(job.lastError() == null);
                    context.completeNow();
                })));
    }
}
