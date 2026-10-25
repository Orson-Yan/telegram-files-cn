package telegram.files;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.TelegramRecord;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static telegram.files.ArchiveOperationsTest.await;

class ArchiveAutoRecoveryTest {
    static final class Setup implements AutoCloseable {
        final ArchiveOperationsTest.Fixture database = new ArchiveOperationsTest.Fixture();
        final List<SettingAutoRecords.Automation> previousRules = AutomationsHolder.INSTANCE.autoRecords().automations;
        final TelegramVerticle telegram = mock(TelegramVerticle.class);
        final TelegramHealthMonitor monitor = mock(TelegramHealthMonitor.class);
        final JsonObject health = JsonObject.of("status", "HEALTHY", "proxy", "Proxy A");
        boolean targetAllowed = true;
        boolean sourceRemoved;
        boolean targetMissing;
        final ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(function -> {
            if (function instanceof TdApi.GetMessage request) {
                if (sourceRemoved) return new TdApi.Error(404, "MESSAGE_NOT_FOUND");
                var message = new TdApi.Message(); message.id = request.messageId; message.chatId = 100; message.content = new TdApi.MessageText(); return message;
            }
            if (function instanceof TdApi.GetMessageProperties) { var properties = new TdApi.MessageProperties(); properties.canBeCopied = true; properties.canBeForwarded = true; return properties; }
            if (function instanceof TdApi.GetChat) {
                if (targetMissing) return new TdApi.Error(404, "CHAT_NOT_FOUND");
                var chat = new TdApi.Chat(); chat.id = 200; chat.type = new TdApi.ChatTypeSupergroup(1, true); return chat;
            }
            if (function instanceof TdApi.GetChatMember) {
                var member = new TdApi.ChatMember(); var creator = new TdApi.ChatMemberStatusCreator(); creator.isMember = targetAllowed; member.status = creator; return member;
            }
            throw new AssertionError("Recovery must only read delivery prerequisites");
        });
        final ArchiveAutoRecovery recovery = new ArchiveAutoRecovery(() -> monitor);
        Setup() throws Exception {
            var rule = new SettingAutoRecords.Automation(); rule.telegramId = 7; rule.chatId = 100; rule.archive.enabled = true; rule.archive.rule.targetChatId = 200;
            AutomationsHolder.INSTANCE.autoRecords().automations = new ArrayList<>(List.of(rule));
            telegram.telegramRecord = mock(TelegramRecord.class); when(telegram.telegramRecord.id()).thenReturn(7L); when(telegram.getId()).thenReturn(7L);
            telegram.client = gateway; when(monitor.snapshot(telegram)).thenAnswer(_ -> health.copy()); TelegramVerticles.add(telegram);
        }
        void due() throws Exception { await(database.operations.execute("UPDATE archive_recovery_state SET next_check_at = 0", Tuple.tuple())); }
        public void close() throws Exception { TelegramVerticles.remove(telegram); AutomationsHolder.INSTANCE.autoRecords().automations = previousRules; database.close(); }
    }

    @Test
    void resumesKnownFailuresAfterConnectionAndPermissionsRecover() throws Exception {
        try (Setup setup = new Setup()) {
            var record = setup.database.add(10, "COPY"); await(setup.database.records.fail(record.id(), false, 0, "TELEGRAM_403", "CHAT_WRITE_FORBIDDEN"));
            setup.health.put("status", "ERROR"); await(setup.recovery.scan()); assertEquals(0, setup.gateway.requests().size());
            setup.health.put("status", "HEALTHY"); setup.targetAllowed = false; setup.due(); await(setup.recovery.scan());
            assertEquals("FAILED", await(setup.database.operations.find(record.id())).status());
            setup.targetAllowed = true; setup.due(); await(setup.recovery.scan());
            assertEquals("RETRY", await(setup.database.operations.find(record.id())).status());
            assertEquals("AUTO_RETRY", await(setup.database.operations.audit(record.id())).getJsonObject(0).getString("action"));
            assertTrue(setup.gateway.requests().stream().noneMatch(request -> request instanceof TdApi.ForwardMessages));
        }
    }

    @Test
    void unknownAndPartiallyDeliveredAlbumsAreNeverAutomaticallyResent() throws Exception {
        try (Setup setup = new Setup()) {
            var unknown = setup.database.add(10, "COPY"); await(setup.database.records.unknown(unknown.id(), "DELIVERY_UNCONFIRMED", "Unknown"));
            await(setup.database.records.enqueue(7, 100, 20, 55, 200, null, "COPY")); await(setup.database.records.enqueue(7, 100, 21, 55, 200, null, "COPY"));
            var album = await(setup.database.records.listRecent(100)).stream().filter(record -> record.sourceAlbumId() == 55).toList();
            await(setup.database.records.complete(album.getFirst().id(), 9001)); await(setup.database.records.fail(album.getLast().id(), false, 0, "MAX_RETRIES_EXCEEDED", "Failure"));
            await(setup.recovery.scan()); assertEquals(0, setup.gateway.requests().size());
            assertEquals("UNKNOWN", await(setup.database.operations.find(unknown.id())).status());
            await(setup.database.records.enqueue(7, 100, 30, 66, 200, null, "COPY")); await(setup.database.records.enqueue(7, 100, 31, 66, 200, null, "COPY"));
            var uncertainAlbum = await(setup.database.records.listRecent(100)).stream().filter(record -> record.sourceAlbumId() == 66).toList();
            await(setup.database.records.unknown(uncertainAlbum.getFirst().id(), "TIMEOUT", "Unknown"));
            await(setup.database.records.fail(uncertainAlbum.getLast().id(), false, 0, "MAX_RETRIES_EXCEEDED", "Failure"));
            await(setup.recovery.scan()); assertEquals(0, setup.gateway.requests().size());
        }
    }

    @Test
    void recoveryRoundsAreDurableAndBoundedButAProxyChangeCanUnlockRecovery() throws Exception {
        try (Setup setup = new Setup()) {
            var record = setup.database.add(10, "COPY");
            for (int round = 1; round <= 4; round++) {
                await(setup.database.records.fail(record.id(), false, 0, "MAX_RETRIES_EXCEEDED", "Failure")); setup.due();
                await(new ArchiveAutoRecovery(() -> setup.monitor).scan());
                assertEquals(round <= 3 ? "RETRY" : "FAILED", await(setup.database.operations.find(record.id())).status());
            }
            setup.health.put("proxy", "Proxy B"); setup.due(); await(setup.recovery.scan());
            assertEquals("RETRY", await(setup.database.operations.find(record.id())).status());
        }
    }

    @Test
    void missingSourceIsSkippedButMissingDestinationKeepsTheQueue() throws Exception {
        try (Setup setup = new Setup()) {
            var source = setup.database.add(10, "COPY"); await(setup.database.records.fail(source.id(), false, 0, "TELEGRAM_400", "MESSAGE_NOT_FOUND"));
            setup.sourceRemoved = true; await(setup.recovery.scan());
            assertEquals("SKIPPED", await(setup.database.operations.find(source.id())).status());
            assertEquals("AUTO_SKIP", await(setup.database.operations.audit(source.id())).getJsonObject(0).getString("action"));
            setup.sourceRemoved = false; setup.targetMissing = true;
            var target = setup.database.add(11, "COPY"); await(setup.database.records.fail(target.id(), false, 0, "TELEGRAM_403", "Failure"));
            await(setup.recovery.scan()); assertEquals("FAILED", await(setup.database.operations.find(target.id())).status());
        }
    }

    @Test
    void pausedTasksAndManuallySkippedRecordsAreNotResurrected() throws Exception {
        try (Setup setup = new Setup()) {
            var record = setup.database.add(10, "COPY"); await(setup.database.records.fail(record.id(), false, 0, "MAX_RETRIES_EXCEEDED", "Failure"));
            AutomationsHolder.INSTANCE.autoRecords().automations.getFirst().archive.enabled = false; await(setup.recovery.scan());
            assertEquals(0, setup.gateway.requests().size());
            AutomationsHolder.INSTANCE.autoRecords().automations.getFirst().archive.enabled = true;
            await(setup.database.operations.skipSelected(7, 100, 200, List.of(record.id()))); await(setup.recovery.scan());
            assertEquals("SKIPPED", await(setup.database.operations.find(record.id())).status()); assertEquals(0, setup.gateway.requests().size());
        }
    }
}
