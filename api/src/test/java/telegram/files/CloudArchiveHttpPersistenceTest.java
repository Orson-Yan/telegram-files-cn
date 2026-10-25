package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.*;
import telegram.files.repository.impl.SettingRepositoryImpl;
import telegram.files.repository.impl.CloudArchiveRepositoryImpl;
import telegram.files.repository.impl.CloudArchiveHistoryRepositoryImpl;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(VertxExtension.class)
class CloudArchiveHttpPersistenceTest {
    @Test
    void newRouteSurvivesActualHttpPersistenceAndOverview(Vertx vertx, VertxTestContext context) {
        verifyCreate(vertx, context, false);
    }

    @Test
    void fullAutomationRequestMustNotReplaceTheExistingDestination(Vertx vertx, VertxTestContext context) {
        verifyCreate(vertx, context, true);
    }

    @Test
    void successfulSaveMustBeVisibleOnTheVeryNextOverviewRequest(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx, 250);
        fixture.start()
                .compose(_ -> fixture.send(HttpMethod.POST, "/7/file/update-auto-settings?chatId=100",
                        JsonObject.of("archiveUpdate", JsonObject.mapFrom(source(300).archive))))
                .compose(response -> {
                    assertEquals(200, response.getInteger("status"));
                    return fixture.send(HttpMethod.GET, "/cloud-archive/overview", null);
                })
                .eventually(fixture::close)
                .onComplete(context.succeeding(response -> context.verify(() -> {
                    assertEquals(2, new JsonObject(response.getString("body")).getJsonArray("rules").size(),
                            "A successful save must wait for the running rules to become visible");
                    context.completeNow();
                })));
    }

    @Test
    void repeatedLegacyCreatesWithEmptyRouteListsRetainEveryDestination(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var first = source(300);
        first.archiveRules = List.of();
        var second = source(400);
        second.archiveRules = List.of();
        fixture.start()
                .compose(_ -> fixture.save(JsonObject.mapFrom(first)))
                .compose(_ -> fixture.save(JsonObject.mapFrom(second)))
                .compose(_ -> fixture.settings.<SettingAutoRecords>getByKey(SettingKey.automation))
                .compose(stored -> {
                    assertEquals(List.of(200L, 300L, 400L), destinations(stored));
                    assertEquals(List.of(200L, 300L, 400L), destinations(AutomationsHolder.INSTANCE.autoRecords()));
                    return fixture.send(HttpMethod.GET, "/cloud-archive/overview", null);
                })
                .eventually(fixture::close)
                .onComplete(context.succeeding(response -> context.verify(() -> {
                    assertEquals(3, new JsonObject(response.getString("body")).getJsonArray("rules").size());
                    context.completeNow();
                })));
    }

    @Test
    void generalSettingsWithEmptyArchiveDefaultsKeepConfiguredDestinations(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var general = new SettingAutoRecords.Automation();
        general.archiveRules = List.of();
        general.download.enabled = true;
        general.download.rule.query = "preserve downloads";
        fixture.start()
                .compose(_ -> fixture.save(JsonObject.of("archiveUpdate", JsonObject.mapFrom(source(300).archive))))
                .compose(_ -> fixture.save(JsonObject.mapFrom(general)))
                .compose(_ -> fixture.settings.<SettingAutoRecords>getByKey(SettingKey.automation))
                .eventually(fixture::close)
                .onComplete(context.succeeding(stored -> context.verify(() -> {
                    assertEquals(List.of(200L, 300L), destinations(stored));
                    assertTrue(stored.getItem(7, 100).download.enabled);
                    assertEquals("preserve downloads", stored.getItem(7, 100).download.rule.query);
                    context.completeNow();
                })));
    }

    @Test
    void explicitEditMovesOnlyTheSelectedDestination(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        fixture.start()
                .compose(_ -> fixture.save(JsonObject.of("archiveUpdate", JsonObject.mapFrom(source(300).archive))))
                .compose(_ -> fixture.save(JsonObject.of("archiveUpdate", JsonObject.mapFrom(source(400).archive),
                        "previousTargetChatId", "300")))
                .compose(_ -> fixture.settings.<SettingAutoRecords>getByKey(SettingKey.automation))
                .eventually(fixture::close)
                .onComplete(context.succeeding(stored -> context.verify(() -> {
                    assertEquals(List.of(200L, 400L), destinations(stored));
                    context.completeNow();
                })));
    }

    private void verifyCreate(Vertx vertx, VertxTestContext context, boolean fullRequest) {
        Fixture fixture = new Fixture(vertx);
        var incoming = source(300);
        JsonObject body = fullRequest ? JsonObject.mapFrom(incoming)
                : JsonObject.of("archiveUpdate", JsonObject.mapFrom(incoming.archive));
        fixture.start()
                .compose(_ -> fixture.send(HttpMethod.POST,
                        "/7/file/update-auto-settings?chatId=100", body))
                .compose(response -> {
                    assertEquals(200, response.getInteger("status"));
                    return Future.succeededFuture();
                })
                .compose(_ -> fixture.settings.<SettingAutoRecords>getByKey(SettingKey.automation))
                .compose(stored -> {
                    assertEquals(List.of(200L, 300L), destinations(stored),
                            "Adding a destination must retain the previous destination in SQLite");
                    assertEquals(List.of(200L, 300L), destinations(AutomationsHolder.INSTANCE.autoRecords()));
                    return fixture.send(HttpMethod.GET, "/cloud-archive/overview", null);
                })
                .compose(response -> {
                    var overview = new JsonObject(response.getString("body"));
                    assertEquals(2, overview.getJsonArray("rules").size());
                    // Reload persisted settings as at application restart, then read the overview again.
                    AutomationsHolder.INSTANCE.autoRecords().automations.clear();
                    return fixture.settings.<SettingAutoRecords>getByKey(SettingKey.automation)
                            .map(stored -> {
                                AutomationsHolder.INSTANCE.onAutoRecordsUpdate(stored);
                                return stored;
                            });
                })
                .compose(_ -> fixture.send(HttpMethod.GET, "/cloud-archive/overview", null))
                .eventually(fixture::close)
                .onComplete(context.succeeding(response -> context.verify(() -> {
                    assertEquals(2, new JsonObject(response.getString("body")).getJsonArray("rules").size());
                    context.completeNow();
                })));
    }

    @Test
    void deletePrimaryAndLastDestinationSurvivesReloadAndRejectsStaleSettings(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        var initial = source(300);
        initial.download.enabled = true;
        fixture.start().compose(_ -> fixture.save(JsonObject.mapFrom(initial)))
                .compose(_ -> fixture.save(JsonObject.of("archiveDeleteTargetChatId", "200")))
                .compose(_ -> fixture.settings.<SettingAutoRecords>getByKey(SettingKey.automation))
                .compose(stored -> {
                    assertEquals(List.of(300L), destinations(stored));
                    assertTrue(stored.getItem(7, 100).download.enabled);
                    AutomationsHolder.INSTANCE.autoRecords().automations.clear();
                    AutomationsHolder.INSTANCE.onAutoRecordsUpdate(stored);
                    assertEquals(List.of(300L), destinations(AutomationsHolder.INSTANCE.autoRecords()));
                    return fixture.send(HttpMethod.POST, "/7/file/update-auto-settings?chatId=100", JsonObject.mapFrom(source(200)));
                }).compose(response -> {
                    assertEquals(400, response.getInteger("status"));
                    return fixture.save(JsonObject.of("archiveDeleteTargetChatId", "300"));
                }).compose(_ -> fixture.settings.<SettingAutoRecords>getByKey(SettingKey.automation))
                .eventually(fixture::close)
                .onComplete(context.succeeding(stored -> context.verify(() -> {
                    assertTrue(destinations(stored).isEmpty());
                    assertTrue(stored.getItem(7, 100).download.enabled);
                    assertEquals(2, stored.getItem(7, 100).archiveDeletedAt.size());
                    context.completeNow();
                })));
    }

    @Test
    void deletionCancelsOnlyTheSelectedRouteAndKeepsCompletedMappings(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        fixture.start().compose(_ -> fixture.save(JsonObject.of("archiveUpdate", JsonObject.mapFrom(source(300).archive))))
                .compose(_ -> fixture.histories.create(7, 100, 0, 200, 0, "{}", "ALL", 0, 500))
                .compose(job -> fixture.archives.stage(7, 100, 0, 10, 0, 200, 0, null, "COPY", job.id()))
                .compose(_ -> fixture.archives.enqueue(7, 100, 20, 0, 200, null, "COPY"))
                .compose(_ -> fixture.archives.enqueue(7, 100, 30, 0, 300, null, "COPY"))
                .compose(_ -> fixture.archives.listRecent(10))
                .compose(records -> fixture.archives.complete(records.stream().filter(record -> record.sourceMessageId() == 20).findFirst().orElseThrow().id(), 220))
                .compose(_ -> fixture.save(JsonObject.of("archiveDeleteTargetChatId", 200)))
                .compose(_ -> fixture.histories.listRecent(10))
                .compose(jobs -> {
                    assertEquals("CANCELLED", jobs.getFirst().status());
                    return fixture.archives.stage(7, 100, 0, 40, 0, 200, 0, null, "COPY", jobs.getFirst().id());
                }).compose(inserted -> {
                    assertFalse(inserted);
                    return fixture.archives.retryAll(7, 100L);
                }).compose(retried -> {
                    assertEquals(0, retried);
                    return fixture.archives.listRecent(10);
                }).compose(records -> {
                    assertEquals("CANCELLED", records.stream().filter(record -> record.sourceMessageId() == 10).findFirst().orElseThrow().status());
                    assertEquals("COMPLETED", records.stream().filter(record -> record.sourceMessageId() == 20).findFirst().orElseThrow().status());
                    assertEquals("PENDING", records.stream().filter(record -> record.targetChatId() == 300).findFirst().orElseThrow().status());
                    return fixture.send(HttpMethod.GET, "/cloud-archive/overview", null);
                }).eventually(fixture::close)
                .onComplete(context.succeeding(response -> context.verify(() -> {
                    var rules = new JsonObject(response.getString("body")).getJsonArray("rules");
                    assertEquals(1, rules.size());
                    assertEquals("300", rules.getJsonObject(0).getString("targetChatId"));
                    assertEquals("QUEUED", rules.getJsonObject(0).getString("waitReason"));
                    context.completeNow();
                })));
    }

    @Test
    void scopedRetryAndClearDoNotTouchAnotherTargetOrUnconfirmedSends(Vertx vertx, VertxTestContext context) {
        Fixture fixture = new Fixture(vertx);
        fixture.start()
                .compose(_ -> fixture.archives.enqueue(7, 100, 10, 0, 200, null, "COPY"))
                .compose(_ -> fixture.archives.enqueue(7, 100, 20, 0, 300, null, "COPY"))
                .compose(_ -> fixture.archives.enqueue(7, 100, 30, 0, 200, null, "COPY"))
                .compose(_ -> fixture.archives.listRecent(10))
                .compose(records -> Future.all(records.stream().map(record -> record.sourceMessageId() == 30
                        ? fixture.archives.unknown(record.id(), "TIMEOUT", "Unconfirmed")
                        : fixture.archives.fail(record.id(), false, 0, "TEST", "Failure")).toList()))
                .compose(_ -> fixture.archives.retryAll(7, 100L, 200L))
                .compose(count -> {
                    assertEquals(1, count);
                    return fixture.archives.listRecent(10, null, 7, 100L, 200L);
                }).compose(records -> {
                    assertEquals(2, records.size());
                    assertEquals(1, records.stream().filter(record -> "UNKNOWN".equals(record.status())).count());
                    return fixture.archives.clearRecords(7, "UNKNOWN", 100L, 200L);
                }).compose(count -> fixture.archives.listRecent(10))
                .eventually(fixture::close)
                .onComplete(context.succeeding(records -> context.verify(() -> {
                    assertEquals(3, records.size());
                    assertEquals(1, records.stream().filter(record -> "UNKNOWN".equals(record.status())).count());
                    assertEquals("FAILED", records.stream().filter(record -> record.targetChatId() == 300).findFirst().orElseThrow().status());
                    assertEquals("RETRY", records.stream().filter(record -> record.targetChatId() == 200 && record.sourceMessageId() == 10).findFirst().orElseThrow().status());
                    context.completeNow();
                })));
    }

    private static List<Long> destinations(SettingAutoRecords records) {
        return records.getArchiveConfiguredItems().stream().map(item -> item.archive.rule.targetChatId).sorted().toList();
    }

    private static SettingAutoRecords.Automation source(long target) {
        var source = new SettingAutoRecords.Automation();
        source.telegramId = 7;
        source.chatId = 100;
        source.archive.enabled = true;
        source.archive.rule.targetChatId = target;
        source.archive.rule.strictOrder = true;
        return source;
    }

    private static final class Fixture {
        private final Vertx vertx;
        private final Pool pool;
        private final SettingRepositoryImpl settings;
        private final CloudArchiveRepository archives;
        private final CloudArchiveHistoryRepository histories;
        private final CloudArchiveHistoryRepository previousHistories = DataVerticle.cloudArchiveHistoryRepository;
        private final HttpClient client;
        private final TelegramVerticle telegram;
        private final HttpVerticle http;
        private final SettingRepository previousSettings = DataVerticle.settingRepository;
        private final CloudArchiveRepository previousArchives = DataVerticle.cloudArchiveRepository;
        private final CloudArchiveSyncRepository previousSyncs = DataVerticle.cloudArchiveSyncRepository;
        private final List<SettingAutoRecords.Automation> previousRules = AutomationsHolder.INSTANCE.autoRecords().automations;
        private Long updateTimer;
        private HttpServer server;

        Fixture(Vertx vertx) {
            this(vertx, 0);
        }

        Fixture(Vertx vertx, long updateDelay) {
            this.vertx = vertx;
            pool = JDBCPool.pool(vertx, new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                    new PoolOptions().setMaxSize(1));
            settings = new SettingRepositoryImpl(pool);
            client = vertx.createHttpClient();
            telegram = new TelegramVerticle(new TelegramRecord(7, "fixture", "http-multi-route", null),
                    () -> new ScriptedTelegramGateway(_ -> new TdApi.Ok()));
            telegram.authorized = true;
            try {
                var field = TelegramVerticle.class.getDeclaredField("clientLifecycle");
                field.setAccessible(true);
                field.set(telegram, java.util.Arrays.stream(field.getType().getEnumConstants())
                        .filter(value -> value.toString().equals("ACTIVE")).findFirst().orElseThrow());
            } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
            telegram.init(vertx, vertx.getOrCreateContext());
            TelegramVerticles.add(telegram);
            http = new HttpVerticle();
            http.init(vertx, vertx.getOrCreateContext());
            DataVerticle.settingRepository = settings;
            archives = new CloudArchiveRepositoryImpl(pool);
            histories = new CloudArchiveHistoryRepositoryImpl(pool);
            DataVerticle.cloudArchiveRepository = archives;
            DataVerticle.cloudArchiveHistoryRepository = histories;
            var syncs = mock(CloudArchiveSyncRepository.class);
            when(syncs.listRoute(anyLong(), anyLong(), anyLong())).thenReturn(Future.succeededFuture(List.of()));
            DataVerticle.cloudArchiveSyncRepository = syncs;
            AutomationsHolder.INSTANCE.autoRecords().automations = new ArrayList<>(List.of(source(200)));
            if (updateDelay == 0) {
                // Use the production consumer, including its acknowledgement, for normal requests.
                try {
                    var method = HttpVerticle.class.getDeclaredMethod("initEventConsumer");
                    method.setAccessible(true);
                    method.invoke(http);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            } else vertx.eventBus().consumer(EventEnum.AUTO_DOWNLOAD_UPDATE.address(), message -> {
                Runnable apply = () -> {
                    AutomationsHolder.INSTANCE.onAutoRecordsUpdate(Json.decodeValue(message.body().toString(), SettingAutoRecords.class));
                    message.reply(null);
                };
                updateTimer = vertx.setTimer(updateDelay, _ -> apply.run());
            });
        }

        Future<Void> start() {
            Router router = Router.router(vertx);
            router.route().handler(BodyHandler.create());
            router.post("/:telegramId/file/update-auto-settings").handler(ctx -> invoke("handleAutoSettingsUpdate", ctx));
            router.get("/cloud-archive/overview").handler(ctx -> invoke("handleCloudArchiveOverview", ctx));
            return pool.query(CloudArchiveRecord.SCHEME).execute()
                    .compose(_ -> pool.query(CloudArchiveHistoryJob.SCHEME).execute())
                    .compose(_ -> pool.query(SettingRecord.SCHEME).execute())
                    .compose(_ -> settings.createOrUpdate("automation", Json.encode(
                            new SettingAutoRecords(List.of(source(200))))))
                    .compose(_ -> vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"))
                    .onSuccess(value -> server = value).mapEmpty();
        }

        private void invoke(String methodName, RoutingContext ctx) {
            try {
                var method = HttpVerticle.class.getDeclaredMethod(methodName, RoutingContext.class);
                method.setAccessible(true);
                method.invoke(http, ctx);
            } catch (Exception failure) {
                ctx.fail(failure.getCause() == null ? failure : failure.getCause());
            }
        }

        Future<JsonObject> send(HttpMethod method, String path, JsonObject body) {
            return client.request(method, server.actualPort(), "127.0.0.1", path)
                    .compose(request -> {
                        request.putHeader("Content-Type", "application/json");
                        return body == null ? request.send() : request.send(Buffer.buffer(body.encode()));
                    })
                    .compose(response -> response.body().map(buffer -> JsonObject.of(
                            "status", response.statusCode(), "body", buffer.toString())));
        }

        Future<Void> save(JsonObject body) {
            return send(HttpMethod.POST, "/7/file/update-auto-settings?chatId=100", body).map(response -> {
                assertEquals(200, response.getInteger("status"), response.getString("body"));
                return null;
            });
        }

        Future<Void> close() {
            if (updateTimer != null) vertx.cancelTimer(updateTimer);
            return (server == null ? Future.<Void>succeededFuture() : server.close())
                    .compose(_ -> client.close()).compose(_ -> pool.close()).onComplete(_ -> {
                        TelegramVerticles.remove(telegram);
                        DataVerticle.settingRepository = previousSettings;
                        DataVerticle.cloudArchiveRepository = previousArchives;
                        DataVerticle.cloudArchiveHistoryRepository = previousHistories;
                        DataVerticle.cloudArchiveSyncRepository = previousSyncs;
                        AutomationsHolder.INSTANCE.autoRecords().automations = previousRules;
                    });
        }
    }
}
