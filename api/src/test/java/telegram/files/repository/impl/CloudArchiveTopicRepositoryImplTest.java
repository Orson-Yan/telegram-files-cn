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
import telegram.files.repository.CloudArchiveTopicMap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(VertxExtension.class)
class CloudArchiveTopicRepositoryImplTest {

    @Test
    void persistsAndUpdatesTopicMapping(Vertx vertx, VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1));
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        CloudArchiveTopicRepositoryImpl repository =
                new CloudArchiveTopicRepositoryImpl(pool, clock);

        pool.query(CloudArchiveTopicMap.SCHEME).execute()
                .compose(_ -> repository.save(
                        7, 100, 11, "Source", 200, 21, "Target", false))
                .compose(_ -> repository.save(
                        7, 100, 11, "Renamed source", 200, 22, "Renamed target", false))
                .compose(_ -> repository.find(7, 100, 11, 200))
                .eventually(pool::close)
                .onComplete(context.succeeding(mapping -> context.verify(() -> {
                    assertEquals(11, mapping.sourceTopicId());
                    assertEquals("Renamed source", mapping.sourceTopicName());
                    assertEquals(22, mapping.targetTopicId());
                    assertEquals("Renamed target", mapping.targetTopicName());
                    assertFalse(mapping.general());
                    assertEquals(1_000, mapping.createdAt());
                    assertEquals(1_000, mapping.updatedAt());
                    context.completeNow();
                })));
    }
}
