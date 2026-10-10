package telegram.files.repository.impl;

import io.vertx.core.Future;
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
import telegram.files.repository.CloudArchiveRepository.RouteOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class CloudArchiveBoundaryTest {
    @Test
    void failedStrictRouteCannotConsumeScanLimitAndStarveUnrelatedHistory(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        Future<Void> inserts = fixture.create();
        for (int id = 1; id <= 201; id++) {
            int sourceId = id;
            inserts = inserts.compose(_ -> repository.enqueue(7, 100, sourceId, 0, 200, null, "COPY").mapEmpty());
        }
        inserts.compose(_ -> repository.listRecent(500))
                .compose(rows -> repository.fail(rows.stream().filter(row -> row.sourceMessageId() == 1)
                        .findFirst().orElseThrow().id(), false, 0, "FORBIDDEN", "failed"))
                .compose(_ -> repository.stage(7, 101, 0, 10, 0, 200, 0, null, "COPY", "job-2"))
                .compose(_ -> repository.releaseHistory("job-2"))
                .compose(_ -> repository.listDue(1_000, 200, List.of(new RouteOrder(7, 100, 200, true, false))))
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(rows -> context.verify(() -> {
                    assertEquals(1, rows.size());
                    assertEquals(101, rows.getFirst().sourceChatId());
                    context.completeNow();
                })));
    }

    @Test
    void activeHistoryHoldAndPausedOrderPolicyAreAppliedBeforeLimit(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.enqueue(7, 100, 10, 0, 200, null, "COPY"))
                .compose(_ -> repository.stage(7, 100, 0, 5, 0, 200, 0, null, "COPY", "job-1"))
                .compose(_ -> repository.enqueue(7, 101, 20, 0, 200, null, "COPY"))
                .compose(_ -> repository.listDue(1_000, 1, List.of(new RouteOrder(7, 100, 200, true, true))))
                .compose(rows -> {
                    context.verify(() -> assertEquals(101, rows.getFirst().sourceChatId()));
                    return repository.listDue(1_000, 10, List.of(new RouteOrder(7, 100, 200, false, false)));
                })
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(rows -> context.verify(() -> {
                    assertEquals(2, rows.size());
                    assertTrue(rows.stream().anyMatch(row -> row.sourceChatId() == 100));
                    context.completeNow();
                })));
    }

    @Test
    void cancellationSkipsKnownFailureAndReleasesStrictLiveButKeepsUnknown(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.stage(7, 100, 0, 10, 0, 200, 0, null, "COPY", "job-1"))
                .compose(_ -> repository.listRecent(10))
                .compose(rows -> repository.fail(rows.getFirst().id(), false, 0, "FORBIDDEN", "failed"))
                .compose(_ -> repository.cancelHistory("job-1"))
                .compose(_ -> repository.enqueue(7, 100, 20, 0, 200, null, "COPY"))
                .compose(_ -> repository.listRecent(10))
                .compose(rows -> {
                    context.verify(() -> {
                        CloudArchiveRecord cancelled = rows.stream().filter(row -> row.sourceMessageId() == 10)
                                .findFirst().orElseThrow();
                        assertEquals("SKIPPED", cancelled.status());
                        assertEquals("HISTORY_CANCELLED", cancelled.lastErrorCode());
                    });
                    var tail = rows.stream().filter(row -> row.sourceMessageId() == 20).findFirst().orElseThrow();
                    return repository.isBatchReady(List.of(tail), 1_000, true);
                })
                .compose(ready -> {
                    context.verify(() -> assertTrue(ready));
                    return repository.stage(7, 100, 0, 15, 0, 200, 0, null, "COPY", "job-1");
                })
                .compose(_ -> repository.listRecent(10))
                .compose(rows -> repository.unknown(rows.stream().filter(row -> row.sourceMessageId() == 15)
                        .findFirst().orElseThrow().id(), "DELIVERY_UNCONFIRMED", "review"))
                .compose(_ -> repository.cancelHistory("job-1"))
                .compose(_ -> repository.listDue(1_000, 10))
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(rows -> context.verify(() -> {
                    assertTrue(rows.isEmpty());
                    context.completeNow();
                })));
    }

    @Test
    void nonStrictAlbumCanSendRemainingMemberAfterPermanentFailure(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.enqueue(7, 100, 10, 777, 200, null, "COPY"))
                .compose(_ -> repository.enqueue(7, 100, 11, 777, 200, null, "COPY"))
                .compose(_ -> repository.enqueue(7, 100, 20, 0, 200, null, "COPY"))
                .compose(_ -> repository.listRecent(10))
                .compose(rows -> {
                    var failed = rows.stream().filter(row -> row.sourceMessageId() == 10).findFirst().orElseThrow();
                    var member = rows.stream().filter(row -> row.sourceMessageId() == 11).findFirst().orElseThrow();
                    return repository.fail(failed.id(), false, 0, "FORBIDDEN", "failed")
                            .compose(_ -> repository.listUnsentAlbum(member, false));
                })
                .compose(album -> {
                    context.verify(() -> assertEquals(List.of(11L), album.stream()
                            .map(CloudArchiveRecord::sourceMessageId).toList()));
                    return repository.isBatchReady(album, 1_000, false)
                            .compose(ready -> {
                                context.verify(() -> assertTrue(ready));
                                return repository.complete(album.getFirst().id(), 9001);
                            });
                })
                .compose(_ -> repository.listDue(1_000, 10))
                .compose(rows -> repository.isBatchReady(rows, 1_000, false))
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(ready -> context.verify(() -> {
                    assertTrue(ready);
                    context.completeNow();
                })));
    }

    @Test
    void partialAlbumCooldownSurvivesInDatabaseWithoutMakingFragmentRetryable(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.enqueue(7, 100, 10, 777, 200, null, "COPY"))
                .compose(_ -> repository.listRecent(10))
                .compose(rows -> repository.fail(rows.getFirst().id(), false, 63_000,
                        "PARTIAL_ALBUM_DELIVERY", "FLOOD_WAIT_60"))
                .compose(_ -> new CloudArchiveRepositoryImpl(fixture.pool).cooldownUntil(7, 1_000))
                .compose(wait -> {
                    context.verify(() -> assertEquals(63_000, wait));
                    return repository.listDue(100_000, 10);
                })
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(rows -> context.verify(() -> {
                    assertTrue(rows.isEmpty());
                    context.completeNow();
                })));
    }

    private static final class Fixture {
        final Pool pool;
        final CloudArchiveRepositoryImpl repository;

        Fixture(Vertx vertx) {
            pool = JDBCPool.pool(vertx, new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                    new PoolOptions().setMaxSize(1));
            repository = new CloudArchiveRepositoryImpl(pool,
                    Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        }

        Future<Void> create() { return pool.query(CloudArchiveRecord.SCHEME).execute().mapEmpty(); }
    }
}
