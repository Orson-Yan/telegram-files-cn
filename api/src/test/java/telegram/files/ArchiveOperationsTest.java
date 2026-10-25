package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import telegram.files.repository.CloudArchiveHistoryJob;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.CloudArchiveRepository;
import telegram.files.repository.impl.ArchiveOperationsRepository;
import telegram.files.repository.impl.CloudArchiveRepositoryImpl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveOperationsTest {
    static <T> T await(Future<T> future) throws Exception { return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS); }

    static final class Fixture implements AutoCloseable {
        final Vertx vertx = Vertx.vertx();
        final Pool pool = JDBCPool.pool(vertx, new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"), new PoolOptions().setMaxSize(1));
        final ArchiveOperationsRepository operations = new ArchiveOperationsRepository(pool);
        final CloudArchiveRepositoryImpl records = new CloudArchiveRepositoryImpl(pool, operations);
        final ArchiveOperationsRepository previous = DataVerticle.archiveOperations;
        final CloudArchiveRepository previousRecords = DataVerticle.cloudArchiveRepository;
        Fixture() throws Exception {
            await(pool.query(CloudArchiveRecord.SCHEME).execute());
            await(pool.query(CloudArchiveHistoryJob.SCHEME).execute());
            await(new ArchiveOperationsRepository.StorageDefinition().createTable(pool));
            DataVerticle.archiveOperations = operations; DataVerticle.cloudArchiveRepository = records;
        }
        CloudArchiveRecord add(long message, String mode) throws Exception {
            await(records.enqueue(7, 100, message, 0, 200, null, mode));
            return await(records.listRecent(100)).stream().filter(record -> record.sourceMessageId() == message).findFirst().orElseThrow();
        }
        String begin(CloudArchiveRecord record) throws Exception {
            assertTrue(await(records.claim(record.id())));
            return await(operations.begin(7, 100, 200, new long[]{record.sourceMessageId()})).get(record.sourceMessageId());
        }
        public void close() throws Exception {
            DataVerticle.archiveOperations = previous; DataVerticle.cloudArchiveRepository = previousRecords;
            await(pool.close()); await(vertx.close());
        }
    }

    private static TdApi.Message sent(long id, long millis) {
        TdApi.Message message = new TdApi.Message(); message.id = id; message.chatId = 200; message.date = (int) (millis / 1000); return message;
    }

    @Test
    void lateConfirmationAfterRestartResolvesUnknownAndCannotBeOverwrittenByTimeout() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY");
            String attempt = f.begin(record);
            await(f.operations.pending(attempt, -1));
            await(f.records.recoverSendingAsUnknown());
            assertEquals("UNKNOWN", await(f.operations.find(record.id())).status());
            await(ArchiveDeliveryService.onUpdate(7, new TdApi.UpdateMessageSendSucceeded(sent(9001, 1792857600000L), -1)));
            await(f.records.unknown(record.id(), "LATE_TIMEOUT", "Old callback"));
            await(f.records.fail(record.id(), true, 0, "LATE_FAILURE", "Old callback"));
            CloudArchiveRecord resolved = await(f.operations.find(record.id()));
            assertEquals("COMPLETED", resolved.status()); assertEquals(9001L, resolved.targetMessageId());
        }
    }

    @Test
    void updateBeforeLateResponseIsReplayedFromDurableEvidence() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY"); String attempt = f.begin(record);
            await(f.records.unknown(record.id(), "DELIVERY_UNCONFIRMED", "No response"));
            await(ArchiveDeliveryService.onUpdate(7, new TdApi.UpdateMessageSendSucceeded(sent(9001, 1792857600000L), -1)));
            assertEquals("UNKNOWN", await(f.operations.find(record.id())).status());
            await(f.operations.pending(attempt, -1));
            assertEquals("COMPLETED", await(f.operations.find(record.id())).status());
        }
    }

    @Test
    void dailyCountsUseDestinationDatesAndSurviveCleanupWithoutDuplicates() throws Exception {
        try (Fixture f = new Fixture()) {
            long before = Instant.parse("2026-10-24T15:59:59Z").toEpochMilli();
            long after = Instant.parse("2026-10-24T16:00:00Z").toEpochMilli();
            CloudArchiveRecord copy = f.add(10, "COPY"); String first = f.begin(copy);
            await(f.operations.confirm(first, 9001, before));
            await(f.operations.confirm(first, 9001, before));
            await(f.records.complete(copy.id(), 9001));
            CloudArchiveRecord forward = f.add(11, "FORWARD"); String second = f.begin(forward);
            await(f.operations.confirm(second, 9002, after));
            JsonObject daily = await(f.operations.daily(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25"), ZoneId.of("Asia/Shanghai"), 7, 100L, 200L));
            assertEquals(1L, daily.getJsonArray("days").getJsonObject(0).getLong("copied"));
            assertEquals(0L, daily.getJsonArray("days").getJsonObject(0).getLong("forwarded"));
            assertEquals(1L, daily.getJsonArray("days").getJsonObject(1).getLong("forwarded"));
            assertEquals(2, await(f.records.clearRecords(7, "ALL", 100L, 200L)));
            assertTrue(await(f.records.listRecent(10)).isEmpty());
            assertFalse(await(f.records.enqueue(7, 100, 10, 0, 200, null, "COPY")));
            assertEquals(1L, await(f.operations.daily(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25"), ZoneId.of("Asia/Shanghai"), 7, 100L, 200L)).getJsonArray("days").getJsonObject(1).getLong("total"));
        }
    }

    @Test
    void oldSuccessBackfillIsIdempotentAndExplicitlyEstimated() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY");
            await(new CloudArchiveRepositoryImpl(f.pool).complete(record.id(), 9001));
            long date = Instant.parse("2026-10-24T16:00:00Z").toEpochMilli();
            await(f.operations.execute("UPDATE telegram_archive_record SET updated_at = ? WHERE id = ?", Tuple.of(date, record.id())));
            await(f.operations.backfill()); await(f.operations.backfill());
            JsonObject day = await(f.operations.daily(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-25"), ZoneId.of("Asia/Shanghai"), 7, null, null)).getJsonArray("days").getJsonObject(0);
            assertEquals(1L, day.getLong("total")); assertEquals(1L, day.getLong("estimated"));
        }
    }

    @Test
    void cleanupPreservesUnknownAndManualSkipPreservesAuditAndDeduplication() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY");
            await(f.records.unknown(record.id(), "TIMEOUT", "Unknown"));
            assertEquals(0, await(f.records.clearRecords(7, "ALL")));
            await(f.operations.manualSkip(record, "User verified the source and chose to skip"));
            assertEquals("SKIPPED", await(f.operations.find(record.id())).status());
            assertEquals("SKIP", await(f.operations.audit(record.id())).getJsonObject(0).getString("action"));
            assertEquals(1, await(f.records.clearRecords(7, "SKIPPED")));
            assertFalse(await(f.records.enqueue(7, 100, 10, 0, 200, null, "COPY")));
            assertThrows(Exception.class, () -> await(f.operations.manualComplete(record, 9001, System.currentTimeMillis(), "Stale review")));
        }
    }

    @Test
    void missingTemporaryMessageRemainsUnknownAndNeverTriggersAnotherSend() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY"); String attempt = f.begin(record);
            await(f.operations.pending(attempt, -1)); await(f.records.recoverSendingAsUnknown());
            ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(_ -> new TdApi.Error(404, "Not found"));
            TelegramVerticle telegram = mock(TelegramVerticle.class);
            when(telegram.getId()).thenReturn(7L); telegram.client = gateway;
            telegram.telegramRecord = mock(telegram.files.repository.TelegramRecord.class);
            when(telegram.telegramRecord.id()).thenReturn(7L);
            TelegramVerticles.add(telegram);
            try {
                assertEquals("UNKNOWN", await(ArchiveDeliveryService.reconcile(record.id())).getString("status"));
                assertEquals("UNKNOWN", await(f.operations.find(record.id())).status());
                assertEquals(1, gateway.requests().size());
                assertInstanceOf(TdApi.GetMessage.class, gateway.requests().getFirst());
            } finally { TelegramVerticles.remove(telegram); }
        }
    }

    @Test
    void knownLateFailureIsSeparatedFromUnconfirmed() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY"); String attempt = f.begin(record);
            await(f.operations.pending(attempt, -1)); await(f.records.recoverSendingAsUnknown());
            await(ArchiveDeliveryService.onUpdate(7, new TdApi.UpdateMessageSendFailed(sent(-1, 0), -1, new TdApi.Error(403, "CHAT_WRITE_FORBIDDEN"))));
            assertEquals("FAILED", await(f.operations.find(record.id())).status());
            JsonObject stats = await(f.records.statistics()); assertEquals(1L, stats.getLong("failed")); assertEquals(0L, stats.getLong("unconfirmed"));
        }
    }

    @Test
    void manualConfirmationCountsOnceOnTheDestinationDate() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY"); await(f.records.unknown(record.id(), "TIMEOUT", "Unknown"));
            long at = Instant.parse("2026-10-24T16:00:00Z").toEpochMilli();
            await(f.operations.manualComplete(record, 9001, at, "Destination message verified"));
            assertEquals("COMPLETED", await(f.operations.find(record.id())).status());
            assertEquals(1, await(f.operations.audit(record.id())).size());
            assertThrows(Exception.class, () -> await(f.operations.manualComplete(record, 9001, at, "Repeated click")));
            assertEquals(1L, await(f.operations.daily(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-25"), ZoneId.of("Asia/Shanghai"), 0, null, null)).getJsonArray("days").getJsonObject(0).getLong("total"));
        }
    }

    @Test
    void manualMessageLinkMustBelongToTheConfiguredDestination() throws Exception {
        try (Fixture f = new Fixture()) {
            CloudArchiveRecord record = f.add(10, "COPY"); await(f.records.unknown(record.id(), "TIMEOUT", "Unknown"));
            TdApi.Message destination = sent(9001, 1792857600000L); destination.chatId = 999;
            ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(_ -> {
                TdApi.MessageLinkInfo info = new TdApi.MessageLinkInfo(); info.message = destination; return info;
            });
            TelegramVerticle telegram = mock(TelegramVerticle.class); telegram.client = gateway;
            telegram.telegramRecord = mock(telegram.files.repository.TelegramRecord.class);
            when(telegram.telegramRecord.id()).thenReturn(7L);
            TelegramVerticles.add(telegram);
            try {
                JsonObject body = JsonObject.of("action", "CONFIRM_SENT", "reason", "Destination verified", "targetMessage", "https://t.me/c/123/5");
                assertThrows(Exception.class, () -> await(ArchiveDeliveryService.resolve(record.id(), body)));
                assertEquals("UNKNOWN", await(f.operations.find(record.id())).status());
                destination.chatId = 200;
                await(ArchiveDeliveryService.resolve(record.id(), body));
                assertEquals("COMPLETED", await(f.operations.find(record.id())).status());
            } finally { TelegramVerticles.remove(telegram); }
        }
    }
}
