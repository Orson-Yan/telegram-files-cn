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
import telegram.files.repository.CloudArchiveRecord;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CloudArchiveRepositoryImplTest {

    @Test
    void stagesReleasesInMessageOrderAndCancelsUnsentHistory(Vertx vertx,
                                                              VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1));
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        CloudArchiveRepositoryImpl repository = new CloudArchiveRepositoryImpl(pool, clock);

        pool.query(CloudArchiveRecord.SCHEME).execute()
                .compose(_ -> repository.stage(
                        7, 100, 11, 30, 0, 200, 21, null, "COPY", "job-1"))
                .compose(_ -> repository.stage(
                        7, 100, 11, 10, 0, 200, 21, null, "COPY", "job-1"))
                .compose(_ -> repository.releaseHistory("job-1"))
                .compose(_ -> repository.listDue(1_000, 10))
                .compose(records -> {
                    context.verify(() -> {
                        assertEquals(2, records.size());
                        assertEquals(10, records.get(0).sourceMessageId());
                        assertEquals(30, records.get(1).sourceMessageId());
                    });
                    return repository.cancelHistory("job-1");
                })
                .compose(_ -> repository.listRecent(10))
                .eventually(pool::close)
                .onComplete(context.succeeding(records -> context.verify(() -> {
                    assertTrue(records.isEmpty());
                    context.completeNow();
                })));
    }

    @Test
    void migrationAddsForumTopicAndHistoryDeliveryColumns(Vertx vertx, VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1));
        String oldScheme = """
                CREATE TABLE telegram_archive_record (
                    id VARCHAR(64) PRIMARY KEY,
                    telegram_id BIGINT NOT NULL,
                    source_chat_id BIGINT NOT NULL,
                    source_message_id BIGINT NOT NULL,
                    source_album_id BIGINT NOT NULL DEFAULT 0,
                    target_chat_id BIGINT NOT NULL,
                    target_message_id BIGINT,
                    file_unique_id VARCHAR(255), mode VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL, attempt_count INT NOT NULL DEFAULT 0,
                    next_attempt_at BIGINT NOT NULL, last_error_code VARCHAR(64),
                    last_error_message VARCHAR(1024), created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE (telegram_id, source_chat_id, source_message_id, target_chat_id)
                )
                """;

        pool.query(oldScheme).execute()
                .compose(_ -> new CloudArchiveRecord.CloudArchiveRecordDefinition().migrate(
                        pool, new Version("0.5.0"), new Version("0.7.0")))
                .compose(_ -> pool.query("PRAGMA table_info(telegram_archive_record)").execute())
                .eventually(pool::close)
                .onComplete(context.succeeding(rows -> context.verify(() -> {
                    var columns = StreamSupport.stream(rows.spliterator(), false)
                            .map(row -> row.getString("name"))
                            .toList();
                    assertTrue(columns.contains("source_topic_id"));
                    assertTrue(columns.contains("target_topic_id"));
                    assertTrue(columns.contains("history_job_id"));
                    assertTrue(columns.contains("delivery_sequence"));
                    context.completeNow();
                })));
    }

    @Test
    void deduplicatesPersistsDeliveryAndRequiresReviewAfterRestart(Vertx vertx,
                                                                   VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        CloudArchiveRepositoryImpl repository = new CloudArchiveRepositoryImpl(pool, clock);

        pool.query(CloudArchiveRecord.SCHEME).execute()
                .compose(_ -> repository.enqueue(7, 100, 11, 0, 200, "file-11", "COPY"))
                .compose(inserted -> {
                    context.verify(() -> assertTrue(inserted));
                    return repository.enqueue(7, 100, 11, 0, 200, "file-11", "COPY");
                })
                .compose(duplicate -> {
                    context.verify(() -> assertFalse(duplicate));
                    return repository.listDue(1_000, 10);
                })
                .compose(records -> {
                    context.verify(() -> {
                        assertEquals(1, records.size());
                        assertEquals("PENDING", records.getFirst().status());
                    });
                    return repository.claim(records.getFirst().id()).map(records.getFirst().id());
                })
                .compose(id -> repository.complete(id, 9001).map(id))
                .compose(_ -> repository.enqueue(7, 100, 12, 0, 200, null, "FORWARD"))
                .compose(_ -> repository.listDue(1_000, 10))
                .compose(records -> repository.claim(records.getFirst().id()))
                .compose(_ -> repository.recoverSendingAsUnknown())
                .compose(_ -> repository.listRecent(10))
                .compose(records -> {
                    context.verify(() -> {
                        assertEquals(2, records.size());
                        CloudArchiveRecord unknown = records.stream()
                                .filter(record -> "UNKNOWN".equals(record.status()))
                                .findFirst()
                                .orElseThrow();
                        CloudArchiveRecord completed = records.stream()
                                .filter(record -> "COMPLETED".equals(record.status()))
                                .findFirst()
                                .orElseThrow();
                        assertEquals(12L, unknown.sourceMessageId());
                        assertEquals(9001L, completed.targetMessageId());
                    });
                    return repository.statistics();
                })
                .eventually(pool::close)
                .onComplete(context.succeeding(statistics -> context.verify(() -> {
                    assertEquals(2L, statistics.getLong("total"));
                    assertEquals(1L, statistics.getLong("completed"));
                    assertEquals(1L, statistics.getLong("failed"));
                    context.completeNow();
                })));
    }
}
