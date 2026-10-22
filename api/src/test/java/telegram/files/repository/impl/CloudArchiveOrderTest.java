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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class CloudArchiveOrderTest {
    @Test
    void recoveryReleasesOnlyTheVerifiedPrefixIncludingLateInserts(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.stage(7, 100, 0, 10, 0, 200, 0, null, "COPY", "sync:7:100:200"))
                .compose(_ -> repository.stage(7, 100, 0, 30, 0, 200, 0, null, "COPY", "sync:7:100:200"))
                .compose(_ -> repository.releaseHistoryThrough("sync:7:100:200", 20))
                .compose(_ -> repository.listDue(1_000, 10))
                .compose(due -> {
                    context.verify(() -> assertEquals(List.of(10L), due.stream()
                            .map(CloudArchiveRecord::sourceMessageId).toList()));
                    return repository.stage(7, 100, 0, 15, 0, 200, 0, null, "COPY", "sync:7:100:200");
                })
                .compose(_ -> repository.releaseHistoryThrough("sync:7:100:200", 20))
                .compose(_ -> repository.listRecent(10))
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(records -> context.verify(() -> {
                    assertEquals("STAGED", records.stream().filter(row -> row.sourceMessageId() == 30)
                            .findFirst().orElseThrow().status());
                    assertEquals("PENDING", records.stream().filter(row -> row.sourceMessageId() == 15)
                            .findFirst().orElseThrow().status());
                    context.completeNow();
                })));
    }

    @Test
    void historyHandoffDoesNotUseHeldLiveMessagesAsAnAlreadyScannedBaseline(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.stage(7, 100, 0, 100, 0, 200, 0, null, "COPY", "job-1"))
                .compose(_ -> repository.stage(7, 100, 0, 300, 0, 200, 0, null, "COPY", "live:job-1"))
                .compose(_ -> repository.releaseHistory("job-1"))
                .compose(_ -> repository.holdHistoryForSync("live:job-1", "sync:7:100:200"))
                .compose(_ -> repository.initialSyncBaseline(7, 100, 0, 200, 300))
                .compose(baseline -> {
                    context.verify(() -> assertEquals(100, baseline));
                    return repository.stage(7, 101, 0, 500, 0, 200, 0, null, "COPY", "sync:7:101:200");
                })
                .compose(_ -> repository.initialSyncBaseline(7, 101, 0, 200, 500))
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(baseline -> context.verify(() -> {
                    assertEquals(499, baseline);
                    context.completeNow();
                })));
    }

    @Test
    void legacyZeroDeliverySequenceUsesNativeSourceIds(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.enqueue(7, 100, 30, 0, 200, null, "COPY"))
                .compose(_ -> repository.enqueue(7, 100, 10, 0, 200, null, "COPY"))
                .compose(_ -> fixture.pool.query("UPDATE telegram_archive_record SET delivery_sequence = 0").execute())
                .compose(_ -> repository.listDue(1_000, 10))
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(records -> context.verify(() -> {
                    assertEquals(List.of(10L, 30L), records.stream().map(CloudArchiveRecord::sourceMessageId).toList());
                    context.completeNow();
                })));
    }

    @Test
    void livePriorityDoesNotOvertakeHistoryOnTheSameRoute(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.stage(7, 100, 0, 10, 0, 200, 0, null, "COPY", "job-1"))
                .compose(_ -> repository.stage(7, 100, 0, 20, 0, 200, 0, null, "COPY", "sync:7:100:200"))
                .compose(_ -> repository.enqueue(7, 100, 30, 0, 200, null, "COPY"))
                .compose(_ -> repository.enqueue(7, 101, 99, 0, 200, null, "COPY"))
                .compose(_ -> repository.releaseHistory("job-1"))
                .compose(_ -> repository.releaseHistory("sync:7:100:200"))
                .compose(_ -> repository.listDue(1_000, 10))
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(records -> context.verify(() -> {
                    assertEquals(101, records.getFirst().sourceChatId());
                    assertEquals(List.of(10L, 20L, 30L), records.stream()
                            .filter(record -> record.sourceChatId() == 100)
                            .map(CloudArchiveRecord::sourceMessageId).toList());
                    context.completeNow();
                })));
    }

    @Test
    void delayedRetryBlocksDatabaseAndPrefetchedTailButNotOtherDestinations(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.enqueue(7, 100, 10, 0, 200, null, "COPY"))
                .compose(_ -> repository.enqueue(7, 100, 20, 0, 200, null, "COPY"))
                .compose(_ -> repository.enqueue(7, 100, 30, 0, 201, null, "COPY"))
                .compose(_ -> repository.listDue(1_000, 10))
                .compose(prefetched -> {
                    CloudArchiveRecord head = prefetched.stream().filter(row -> row.sourceMessageId() == 10).findFirst().orElseThrow();
                    CloudArchiveRecord tail = prefetched.stream().filter(row -> row.sourceMessageId() == 20).findFirst().orElseThrow();
                    return repository.claim(head.id())
                            .compose(_ -> repository.fail(head.id(), true, 30_000, "TIMEOUT", "retry later"))
                            .compose(_ -> repository.listDue(1_000, 10))
                            .compose(due -> {
                                context.verify(() -> assertEquals(List.of(30L), due.stream()
                                        .map(CloudArchiveRecord::sourceMessageId).toList()));
                                return repository.isBatchReady(List.of(tail), 1_000, true);
                            })
                            .compose(ready -> {
                                context.verify(() -> assertFalse(ready));
                                return repository.isBatchReady(List.of(head), 1_000, true);
                            })
                            .compose(ready -> {
                                context.verify(() -> assertFalse(ready));
                                return repository.listDue(30_000, 10);
                            })
                            .compose(due -> {
                                context.verify(() -> assertEquals(List.of(10L, 20L), due.stream()
                                        .filter(row -> row.targetChatId() == 200)
                                        .map(CloudArchiveRecord::sourceMessageId).toList()));
                                return repository.claim(head.id());
                            })
                            .compose(_ -> repository.isBatchReady(List.of(tail), 30_000, true))
                            .compose(ready -> {
                                context.verify(() -> assertFalse(ready));
                                return repository.complete(head.id(), 9001);
                            })
                            .compose(_ -> repository.isBatchReady(List.of(tail), 30_000, true));
                })
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(ready -> context.verify(() -> {
                    assertTrue(ready);
                    context.completeNow();
                })));
    }

    @Test
    void strictRouteWaitsForStagedAndFailedPredecessorsAndUnknownAlwaysBlocks(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.stage(7, 100, 0, 10, 0, 200, 0, null, "COPY", "job-1"))
                .compose(_ -> repository.enqueue(7, 100, 20, 0, 200, null, "COPY"))
                .compose(_ -> repository.listRecent(10))
                .compose(records -> {
                    CloudArchiveRecord head = records.stream().filter(row -> row.sourceMessageId() == 10).findFirst().orElseThrow();
                    CloudArchiveRecord tail = records.stream().filter(row -> row.sourceMessageId() == 20).findFirst().orElseThrow();
                    return repository.isBatchReady(List.of(tail), 1_000, true)
                            .compose(ready -> {
                                context.verify(() -> assertFalse(ready));
                                return repository.isBatchReady(List.of(tail), 1_000, false);
                            })
                            .compose(ready -> {
                                context.verify(() -> assertTrue(ready));
                                return repository.fail(head.id(), false, 0, "FORBIDDEN", "failed");
                            })
                            .compose(_ -> repository.isBatchReady(List.of(tail), 1_000, true))
                            .compose(ready -> {
                                context.verify(() -> assertFalse(ready));
                                return repository.unknown(head.id(), "PROCESS_RESTARTED", "review");
                            })
                            .compose(_ -> repository.listDue(1_000, 10))
                            .compose(due -> {
                                context.verify(() -> assertTrue(due.isEmpty()));
                                return repository.isBatchReady(List.of(tail), 1_000, false);
                            });
                })
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(ready -> context.verify(() -> {
                    assertFalse(ready);
                    context.completeNow();
                })));
    }

    @Test
    void albumLookupIncludesMembersOutsideScanLimitAndAcrossHistoryLiveKeys(
            Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var repository = fixture.repository;
        fixture.create()
                .compose(_ -> repository.stage(7, 100, 0, 10, 777, 200, 0, null, "COPY", "job-1"))
                .compose(_ -> repository.stage(7, 100, 0, 11, 777, 200, 0, null, "COPY", "live:job-1"))
                .compose(_ -> repository.enqueue(7, 100, 12, 777, 200, null, "COPY"))
                .compose(_ -> repository.releaseHistory("job-1"))
                .compose(_ -> repository.listDue(1_000, 1))
                .compose(due -> repository.listUnsentAlbum(due.getFirst()))
                .compose(album -> {
                    context.verify(() -> assertEquals(List.of(10L, 11L, 12L), album.stream()
                            .map(CloudArchiveRecord::sourceMessageId).toList()));
                    return repository.isBatchReady(album, 1_000, true)
                            .compose(ready -> {
                                context.verify(() -> assertFalse(ready));
                                return repository.releaseHistory("live:job-1");
                            })
                            .compose(_ -> repository.isBatchReady(album, 1_000, true));
                })
                .eventually(fixture.pool::close)
                .onComplete(context.succeeding(ready -> context.verify(() -> {
                    assertTrue(ready);
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

        Future<Void> create() { return pool.query(CloudArchiveRecord.SCHEME).execute()
                .compose(_ -> pool.query(telegram.files.repository.CloudArchiveHistoryJob.SCHEME).execute()).mapEmpty(); }
    }
}
