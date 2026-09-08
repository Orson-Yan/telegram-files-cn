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
import telegram.files.repository.CloudArchiveHistoryJob;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CloudArchiveHistoryRepositoryImplTest {

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
                .compose(_ -> repository.create(7, 100, 11, 200, 12, "{}", 500))
                .compose(job -> repository.start(job.id()).map(job))
                .compose(job -> repository.advance(job.id(), 900, 50, 20, 18, false).map(job))
                .compose(job -> repository.transition(job.id(), "pause").map(job))
                .compose(job -> repository.transition(job.id(), "resume").map(job))
                .compose(job -> repository.start(job.id()).map(job))
                .compose(job -> repository.advance(job.id(), 800, 50, 10, 9, true))
                .compose(_ -> repository.listRecent(10))
                .eventually(pool::close)
                .onComplete(context.succeeding(jobs -> context.verify(() -> {
                    assertEquals(1, jobs.size());
                    CloudArchiveHistoryJob job = jobs.getFirst();
                    assertEquals("COMPLETED", job.status());
                    assertEquals(100, job.scannedCount());
                    assertEquals(30, job.matchedCount());
                    assertEquals(27, job.queuedCount());
                    assertEquals(800, job.fromMessageId());
                    assertTrue(job.lastError() == null);
                    context.completeNow();
                })));
    }
}
