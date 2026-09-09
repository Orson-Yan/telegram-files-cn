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
import telegram.files.repository.CloudArchiveSyncState;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class CloudArchiveSyncRepositoryImplTest {

    @Test
    void persistsAndAdvancesAnOutageRecoveryCursor(Vertx vertx,
                                                    VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1));
        CloudArchiveSyncRepositoryImpl repository = new CloudArchiveSyncRepositoryImpl(
                pool, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        pool.query(CloudArchiveSyncState.SCHEME).execute()
                .compose(_ -> repository.ensure(7, 100, 11, 200, 100))
                .compose(state -> repository.beginRecovery(state, 150)
                        .compose(_ -> repository.find(7, 100, 11, 200)))
                .compose(state -> {
                    context.verify(() -> {
                        assertEquals("RECOVERING", state.status());
                        assertEquals(100L, state.lastObservedMessageId());
                        assertEquals(150L, state.recoveryTargetMessageId());
                    });
                    return repository.advanceRecovery(state, 125, 25, 20, 18)
                            .compose(_ -> repository.find(7, 100, 11, 200));
                })
                .compose(state -> {
                    context.verify(() -> {
                        assertEquals(125L, state.recoveryCursorMessageId());
                        assertEquals(25, state.scannedCount());
                        assertEquals(20, state.matchedCount());
                        assertEquals(18, state.queuedCount());
                    });
                    return repository.completeRecovery(state)
                            .compose(_ -> repository.find(7, 100, 11, 200));
                })
                .eventually(pool::close)
                .onComplete(context.succeeding(state -> context.verify(() -> {
                    assertEquals("LIVE", state.status());
                    assertEquals(150L, state.lastObservedMessageId());
                    assertEquals(0L, state.recoveryCursorMessageId());
                    context.completeNow();
                })));
    }
}
