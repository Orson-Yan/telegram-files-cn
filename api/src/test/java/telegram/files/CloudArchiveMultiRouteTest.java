package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.ext.web.RoutingContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import telegram.files.repository.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(VertxExtension.class)
class CloudArchiveMultiRouteTest {
    private SettingRepository previousSettings;
    private SettingRepository settings;
    private String saved;

    @BeforeEach
    void setup(Vertx vertx) {
        vertx.eventBus().consumer(EventEnum.AUTO_DOWNLOAD_UPDATE.address(), message -> message.reply(null));
        previousSettings = DataVerticle.settingRepository;
        settings = mock(SettingRepository.class);
        DataVerticle.settingRepository = settings;
        saved = Json.encode(records());
        when(settings.<SettingAutoRecords>getByKey(SettingKey.automation))
                .thenAnswer(_ -> Future.succeededFuture(Json.decodeValue(saved, SettingAutoRecords.class)));
        when(settings.createOrUpdate(eq(SettingKey.automation.name()), anyString())).thenAnswer(invocation -> {
            saved = invocation.getArgument(1);
            return Future.succeededFuture(new SettingRecord(SettingKey.automation.name(), saved));
        });
    }

    @AfterEach
    void restore() {
        DataVerticle.settingRepository = previousSettings;
    }

    @Test
    void jsonRoundTripExpandsRoutesWithoutDuplicatingDownloadAutomation() {
        var source = source();
        source.download.enabled = true;
        source.archiveRules = List.of(config(300));
        var stored = Json.decodeValue(Json.encode(new SettingAutoRecords(List.of(source))), SettingAutoRecords.class);
        assertEquals(2, stored.getArchiveConfiguredItems().size());
        assertEquals(2, stored.getArchiveEnabledItems(7, 100).size());
        assertEquals(1, stored.getDownloadEnabledItems().size());
        assertEquals(1, stored.getItems(7).size());
        assertEquals(300, stored.getArchiveItem(7, 100, 300).archive.rule.targetChatId);
        assertNull(stored.getArchiveItem(7, 100, 400));
        stored.getArchiveItem(7, 100, 200).archive.enabled = false;
        assertEquals(300, stored.getArchiveEnabledItems().getFirst().archive.rule.targetChatId);
        assertEquals(2, stored.getArchiveConfiguredItems().size());
    }

    @Test
    void runningAutomationsRetainAdditionalDestinationsAcrossLegacyUpdates() throws Exception {
        var constructor = AutomationsHolder.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var holder = constructor.newInstance();
        holder.autoRecords().add(source());
        var updated = source();
        updated.archiveRules = List.of(config(300));
        try (MockedStatic<TelegramVerticles> ignored = mockStatic(TelegramVerticles.class)) {
            holder.onAutoRecordsUpdate(new SettingAutoRecords(List.of(updated)));
            assertEquals(2, holder.autoRecords().getArchiveEnabledItems().size());
            holder.onAutoRecordsUpdate(records());
            assertEquals(2, holder.autoRecords().getArchiveEnabledItems().size());
            updated.archiveRules = List.of();
            holder.onAutoRecordsUpdate(new SettingAutoRecords(List.of(updated)));
            assertEquals(1, holder.autoRecords().getArchiveConfiguredItems().size());
        }
    }

    @Test
    void historyEndpointSelectsTheDestinationAndRejectsAmbiguousLegacyRequests() throws Exception {
        var oldRules = AutomationsHolder.INSTANCE.autoRecords().automations;
        var oldHistories = DataVerticle.cloudArchiveHistoryRepository;
        try {
            var source = source();
            source.archiveRules = List.of(config(300));
            AutomationsHolder.INSTANCE.autoRecords().automations = new ArrayList<>(List.of(source));
            var histories = mock(CloudArchiveHistoryRepository.class);
            DataVerticle.cloudArchiveHistoryRepository = histories;
            when(histories.create(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                    anyString(), anyInt(), anyInt())).thenReturn(Promise.<CloudArchiveHistoryJob>promise().future());
            historyRequest(JsonObject.of("telegramId", "7", "sourceChatId", "100",
                    "targetChatId", "300", "scanMode", "ALL"));
            verify(histories).create(7, 100, 0, 300, 0,
                    Json.encode(source.archiveRules.getFirst().rule), "ALL", 0, 500);
            clearInvocations(histories);
            RoutingContext ambiguous = historyRequest(JsonObject.of("telegramId", "7", "sourceChatId", "100"));
            verify(ambiguous.response()).setStatusCode(400);
            verifyNoInteractions(histories);

            source.archiveRules.getFirst().enabled = false;
            RoutingContext disabled = historyRequest(JsonObject.of("telegramId", "7", "sourceChatId", "100",
                    "targetChatId", "300"));
            verify(disabled.response()).setStatusCode(400);
            verifyNoInteractions(histories);
            // A legacy request remains valid when there is exactly one enabled route.
            historyRequest(JsonObject.of("telegramId", "7", "sourceChatId", "100", "scanMode", "ALL"));
            verify(histories).create(7, 100, 0, 200, 0, Json.encode(source.archive.rule), "ALL", 0, 500);
        } finally {
            AutomationsHolder.INSTANCE.autoRecords().automations = oldRules;
            DataVerticle.cloudArchiveHistoryRepository = oldHistories;
        }
    }

    private static RoutingContext historyRequest(JsonObject body) throws Exception {
        RoutingContext context = mock(RoutingContext.class, RETURNS_DEEP_STUBS);
        when(context.body().asJsonObject()).thenReturn(body);
        var method = HttpVerticle.class.getDeclaredMethod("handleCloudArchiveHistoryCreate", RoutingContext.class);
        method.setAccessible(true);
        method.invoke(new HttpVerticle(), context);
        return context;
    }

    @Test
    void savingTwoRoutesAndLegacyUpdatesRetainsBothDestinations(Vertx vertx, VertxTestContext context) {
        TelegramVerticle telegram = telegram(vertx);
        var source = source();
        source.archiveRules = List.of(config(300));
        JsonObject legacy = JsonObject.mapFrom(source());
        legacy.remove("archiveRules");
        telegram.updateAutoSettings(100L, JsonObject.mapFrom(source))
                // A legacy client has no archiveRules field and must not erase the second route.
                .compose(_ -> telegram.updateAutoSettings(100L, legacy))
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    var result = Json.decodeValue(saved, SettingAutoRecords.class);
                    assertEquals(List.of(200L, 300L), result.getArchiveConfiguredItems().stream()
                            .map(item -> item.archive.rule.targetChatId).toList());
                    assertTrue(result.getArchiveItem(7, 100, 300).archive.rule.recoveryEnabled);
                    context.completeNow();
                })));
    }

    @Test
    void duplicateDestinationIsRejectedBeforePersisting(Vertx vertx, VertxTestContext context) {
        var source = source();
        source.archiveRules = List.of(config(200));
        telegram(vertx).updateAutoSettings(100L, JsonObject.mapFrom(source))
                .onComplete(context.failing(failure -> context.verify(() -> {
                    assertTrue(failure.getMessage().contains("already exists"));
                    verify(settings, never()).createOrUpdate(anyString(), anyString());
                    context.completeNow();
                })));
    }

    @Test
    void concurrentDestinationUpdatesMergeAfterThePreviousSaveCompletes(Vertx vertx, VertxTestContext context) {
        var initial = records();
        initial.automations.getFirst().download.enabled = true;
        initial.automations.getFirst().download.rule.query = "keep downloads";
        saved = Json.encode(initial);
        Promise<SettingRecord> firstWrite = Promise.promise();
        int[] writes = {0};
        when(settings.createOrUpdate(eq(SettingKey.automation.name()), anyString())).thenAnswer(invocation -> {
            saved = invocation.getArgument(1);
            return ++writes[0] == 1 ? firstWrite.future()
                    : Future.succeededFuture(new SettingRecord(SettingKey.automation.name(), saved));
        });
        var telegram = telegram(vertx);
        Future<Void> first = telegram.updateAutoSettings(100L,
                JsonObject.of("archiveUpdate", JsonObject.mapFrom(config(300))));
        Future<Void> second = telegram.updateAutoSettings(100L,
                JsonObject.of("archiveUpdate", JsonObject.mapFrom(config(400))));
        verify(settings, times(1)).getByKey(SettingKey.automation);
        assertFalse(second.isComplete());
        firstWrite.complete(new SettingRecord(SettingKey.automation.name(), saved));
        Future.all(first, second).onComplete(context.succeeding(_ -> context.verify(() -> {
            var result = Json.decodeValue(saved, SettingAutoRecords.class);
            assertEquals(List.of(200L, 300L, 400L), result.getArchiveConfiguredItems().stream()
                    .map(item -> item.archive.rule.targetChatId).toList());
            assertTrue(result.getItem(7, 100).download.enabled);
            assertEquals("keep downloads", result.getItem(7, 100).download.rule.query);
            context.completeNow();
        })));
    }

    @Test
    void destinationEditAndPauseRetainTheOtherRouteAfterARejectedDuplicate(Vertx vertx, VertxTestContext context) {
        var initial = records();
        initial.automations.getFirst().archiveRules = List.of(config(300));
        saved = Json.encode(initial);
        var telegram = telegram(vertx);
        var paused = config(400);
        paused.enabled = false;
        telegram.updateAutoSettings(100L, JsonObject.of("archiveUpdate", JsonObject.mapFrom(config(200))))
                .compose(_ -> Future.<Void>failedFuture(new AssertionError("Duplicate route was accepted")))
                .recover(failure -> failure instanceof IllegalArgumentException
                        ? Future.succeededFuture() : Future.failedFuture(failure))
                .compose(_ -> telegram.updateAutoSettings(100L, JsonObject.of(
                        "archiveUpdate", JsonObject.mapFrom(paused), "previousTargetChatId", "300")))
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    var result = Json.decodeValue(saved, SettingAutoRecords.class);
                    assertEquals(2, result.getArchiveConfiguredItems().size());
                    assertEquals(200L, result.getArchiveEnabledItems().getFirst().archive.rule.targetChatId);
                    assertFalse(result.getArchiveItem(7, 100, 400).archive.enabled);
                    assertNull(result.getArchiveItem(7, 100, 300));
                    context.completeNow();
                })));
    }

    @Test
    void loopThroughAnAdditionalDestinationIsRejected(Vertx vertx, VertxTestContext context) {
        var next = source();
        next.chatId = 200;
        next.archive = config(300);
        next.archiveRules = List.of(config(100));
        saved = Json.encode(new SettingAutoRecords(List.of(next)));
        telegram(vertx).updateAutoSettings(100L, JsonObject.mapFrom(source()))
                .onComplete(context.failing(failure -> context.verify(() -> {
                    assertTrue(failure.getMessage().contains("forwarding loop"));
                    verify(settings, never()).createOrUpdate(anyString(), anyString());
                    context.completeNow();
                })));
    }

    @Test
    void oneSourceUpdateStagesBothStrictRoutesWithSeparateRecoveryKeys(Vertx vertx) {
        var oldRules = AutomationsHolder.INSTANCE.autoRecords().automations;
        var oldArchives = DataVerticle.cloudArchiveRepository;
        var oldHistories = DataVerticle.cloudArchiveHistoryRepository;
        var oldSyncs = DataVerticle.cloudArchiveSyncRepository;
        try (MockedStatic<TelegramVerticles> accounts = mockStatic(TelegramVerticles.class)) {
            var source = source();
            source.archiveRules = List.of(config(300));
            AutomationsHolder.INSTANCE.autoRecords().automations = new ArrayList<>(List.of(source));
            var archives = mock(CloudArchiveRepository.class);
            var histories = mock(CloudArchiveHistoryRepository.class);
            var syncs = mock(CloudArchiveSyncRepository.class);
            DataVerticle.cloudArchiveRepository = archives;
            DataVerticle.cloudArchiveHistoryRepository = histories;
            DataVerticle.cloudArchiveSyncRepository = syncs;
            when(histories.findActive(eq(7L), eq(100L), anyLong())).thenReturn(Future.succeededFuture(null));
            when(syncs.find(eq(7L), eq(100L), eq(0L), anyLong())).thenReturn(Future.succeededFuture(null));
            // Keep the enqueue unfinished so this test only exercises event fanout, not the scheduler.
            when(archives.stage(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                    anyLong(), nullable(String.class), anyString(), anyString(), anyString()))
                    .thenReturn(Promise.<Boolean>promise().future());
            TdApi.Message message = new TdApi.Message();
            message.chatId = 100;
            message.id = 20;
            message.content = new TdApi.MessageText(new TdApi.FormattedText("hello", new TdApi.TextEntity[0]), null, null);
            var gateway = new ScriptedTelegramGateway(_ -> message);
            TelegramVerticle telegram = telegram(vertx);
            telegram.client = gateway;
            accounts.when(() -> TelegramVerticles.get(7)).thenReturn(Optional.of(telegram));
            var worker = new AutoCloudArchiveVerticle();
            worker.init(vertx, vertx.getOrCreateContext());
            worker.onNewMessage(JsonObject.of("telegramId", 7L, "chatId", 100L, "messageId", 20L));

            assertEquals(1, gateway.requests().size());
            verify(archives).stage(7, 100, 0, 20, 0, 200, 0, null, "COPY", "MERGE", "sync:7:100:200");
            verify(archives).stage(7, 100, 0, 20, 0, 300, 0, null, "COPY", "MERGE", "sync:7:100:300");
            verify(histories).findActive(7, 100, 200);
            verify(histories).findActive(7, 100, 300);

            // The same album must keep one debounce buffer per destination.
            Vertx timers = mock(Vertx.class);
            worker.init(timers, vertx.getOrCreateContext());
            message.mediaAlbumId = 777;
            worker.onNewMessage(JsonObject.of("telegramId", 7L, "chatId", 100L, "messageId", 20L));
            verify(timers, times(2)).setTimer(anyLong(), any());
        } finally {
            AutomationsHolder.INSTANCE.autoRecords().automations = oldRules;
            DataVerticle.cloudArchiveRepository = oldArchives;
            DataVerticle.cloudArchiveHistoryRepository = oldHistories;
            DataVerticle.cloudArchiveSyncRepository = oldSyncs;
        }
    }

    private static TelegramVerticle telegram(Vertx vertx) {
        var telegram = new TelegramVerticle("multi-route-fixture", () -> new ScriptedTelegramGateway(_ -> new TdApi.Ok()));
        telegram.telegramRecord = new TelegramRecord(7, "test", "multi-route-fixture", null);
        telegram.init(vertx, vertx.getOrCreateContext());
        return telegram;
    }

    private static SettingAutoRecords records() {
        return new SettingAutoRecords(new ArrayList<>(List.of(source())));
    }

    private static SettingAutoRecords.Automation source() {
        var source = new SettingAutoRecords.Automation();
        source.telegramId = 7;
        source.chatId = 100;
        source.archive = config(200);
        return source;
    }

    private static SettingAutoRecords.ArchiveConfig config(long target) {
        var config = new SettingAutoRecords.ArchiveConfig();
        config.enabled = true;
        config.rule.targetChatId = target;
        config.rule.strictOrder = true;
        config.rule.recoveryEnabled = false;
        return config;
    }
}
