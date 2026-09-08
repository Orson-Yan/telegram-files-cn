package telegram.files.repository.impl;

import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.CloudArchiveRecord;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CloudArchiveRepositoryImplTest {

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
