package telegram.files;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.http.HttpServer;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.Test;
import telegram.files.repository.SettingAutoRecords;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static telegram.files.ArchiveOperationsTest.await;

class BarkNotificationServiceTest {
    private static final class Setup implements AutoCloseable {
        final ArchiveOperationsTest.Fixture database = new ArchiveOperationsTest.Fixture();
        final List<JsonObject> received = new ArrayList<>();
        final HttpServer server;
        final BarkNotificationService service;
        final List<SettingAutoRecords.Automation> oldRules = AutomationsHolder.INSTANCE.autoRecords().automations;
        final telegram.files.repository.CloudArchiveHistoryRepository oldHistory = DataVerticle.cloudArchiveHistoryRepository;
        int status = 200;
        Setup() throws Exception {
            DataVerticle.cloudArchiveHistoryRepository = new telegram.files.repository.impl.CloudArchiveHistoryRepositoryImpl(database.pool);
            TelegramHealthMonitor health = mock(TelegramHealthMonitor.class);
            when(health.snapshots()).thenReturn(new JsonArray());
            service = new BarkNotificationService(database.vertx, () -> health);
            server = await(database.vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
                received.add(body.toJsonObject());
                request.response().setStatusCode(status).putHeader("Content-Type", "application/json")
                        .end(JsonObject.of("code", status).encode());
            })).listen(0, "127.0.0.1"));
            var rule = new SettingAutoRecords.Automation(); rule.telegramId = 7; rule.chatId = 100;
            rule.archive.enabled = true; rule.archive.rule.targetChatId = 200; rule.archive.rule.strictOrder = true;
            AutomationsHolder.INSTANCE.autoRecords().automations = new ArrayList<>(List.of(rule));
            await(service.save(BarkNotificationService.defaults().put("enabled", true)
                    .put("serverUrl", "http://127.0.0.1:" + server.actualPort()).put("deviceKey", "fixture-private-device-key")));
        }
        void blocked() throws Exception {
            var record = database.add(10, "COPY");
            await(database.records.unknown(record.id(), "DELIVERY_UNCONFIRMED", "Test"));
            await(database.operations.execute("UPDATE telegram_archive_record SET updated_at = ? WHERE id = ?", Tuple.of(System.currentTimeMillis() - 600_000, record.id())));
        }
        public void close() throws Exception {
            service.stop(); await(server.close());
            AutomationsHolder.INSTANCE.autoRecords().automations = oldRules; DataVerticle.cloudArchiveHistoryRepository = oldHistory; database.close();
        }
    }

    @Test
    void storesOnlyEncryptedDeviceKeyAndNeverReturnsItToTheBrowser() throws Exception {
        try (Setup setup = new Setup()) {
            JsonObject settings = await(setup.service.settings());
            assertTrue(settings.getBoolean("keyConfigured"));
            assertFalse(settings.encode().contains("fixture-private-device-key"));
            assertFalse(settings.containsKey("encryptedKey"));
            String stored = await(setup.database.operations.query("SELECT encrypted_key FROM notification_settings", Tuple.tuple()))
                    .getJsonObject(0).getString("encrypted_key");
            assertFalse(stored.contains("fixture-private-device-key"));
            await(setup.service.save(settings.put("deviceKey", "")));
            await(setup.service.test());
            assertEquals("fixture-private-device-key", setup.received.getFirst().getString("device_key"));
        }
    }

    @Test
    void alertAndRecoveryAreDeduplicatedAcrossChecksAndServiceRestart() throws Exception {
        try (Setup setup = new Setup()) {
            setup.blocked(); await(setup.service.tick()); await(setup.service.tick());
            assertEquals(1, setup.received.size());
            TelegramHealthMonitor health = mock(TelegramHealthMonitor.class);
            when(health.snapshots()).thenReturn(new JsonArray());
            var restarted = new BarkNotificationService(setup.database.vertx, () -> health);
            try {
                await(restarted.tick()); assertEquals(1, setup.received.size());
                var record = await(setup.database.records.listRecent(10)).getFirst();
                await(setup.database.records.complete(record.id(), 9001));
                await(restarted.tick()); await(restarted.tick());
                assertEquals(2, setup.received.size());
                assertEquals("异常已解除", setup.received.get(1).getString("title"));
            } finally { restarted.stop(); }
        }
    }

    @Test
    void failedPushHasBoundedRetriesAndDoesNotBlockTheArchiveQueue() throws Exception {
        try (Setup setup = new Setup()) {
            setup.status = 503; setup.blocked();
            for (int i = 0; i < 4; i++) {
                await(setup.database.operations.execute("UPDATE notification_incident SET next_attempt_at = 0", Tuple.tuple()));
                await(setup.service.tick());
            }
            assertEquals(3, setup.received.size());
            var incident = await(setup.service.settings()).getJsonArray("incidents").getJsonObject(0);
            assertEquals(3, ((Number) incident.getValue("attempts")).intValue());
            assertFalse(incident.encode().contains("fixture-private-device-key"));
            assertEquals("UNKNOWN", await(setup.database.records.listRecent(10)).getFirst().status());
            setup.status = 200;
            await(setup.service.save(await(setup.service.settings())));
            await(setup.service.tick());
            assertEquals(4, setup.received.size(), "Saving repaired settings must re-enable failed notification delivery");
        }
    }

    @Test
    void shortUnconfirmedStateAndDisabledNotificationsStayQuiet() throws Exception {
        try (Setup setup = new Setup()) {
            var record = setup.database.add(10, "COPY"); await(setup.database.records.unknown(record.id(), "TIMEOUT", "Test"));
            await(setup.service.tick()); assertEquals(0, setup.received.size());
            await(setup.service.save(await(setup.service.settings()).put("enabled", false)));
            await(setup.service.tick()); assertEquals(0, setup.received.size());
        }
    }

    @Test
    void disabledEventDoesNotGenerateAnAlertAndInvalidSettingsAreRejected() throws Exception {
        try (Setup setup = new Setup()) {
            setup.blocked(); await(setup.service.save(await(setup.service.settings()).put("events", new JsonArray())));
            await(setup.service.tick()); assertEquals(0, setup.received.size());
            assertThrows(Exception.class, () -> await(setup.service.save(await(setup.service.settings()).put("serverUrl", "file:///etc/passwd"))));
            assertThrows(Exception.class, () -> await(setup.service.save(await(setup.service.settings()).put("unconfirmedMinutes", 0))));
        }
    }

    @Test
    void permanentDeliveryFailureNotifiesEvenWhenStrictOrderIsDisabled() throws Exception {
        try (Setup setup = new Setup()) {
            AutomationsHolder.INSTANCE.autoRecords().automations.getFirst().archive.rule.strictOrder = false;
            var record = setup.database.add(10, "COPY");
            await(setup.database.records.fail(record.id(), false, 0, "403", "CHAT_WRITE_FORBIDDEN"));
            await(setup.service.tick());
            assertEquals(1, setup.received.size());
            assertEquals("转发出现永久失败，需要处理", setup.received.getFirst().getString("title"));
        }
    }
}
