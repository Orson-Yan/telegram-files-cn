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
            telegram.init(vertx, vertx.getOrCreateContext());
            TelegramVerticles.add(telegram);
            http = new HttpVerticle();
            http.init(vertx, vertx.getOrCreateContext());
            DataVerticle.settingRepository = settings;
            var archives = mock(CloudArchiveRepository.class);
            when(archives.statistics()).thenReturn(Future.succeededFuture(JsonObject.of(
                    "total", 0, "pending", 0, "completed", 0, "failed", 0, "skipped", 0)));
            DataVerticle.cloudArchiveRepository = archives;
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
            return pool.query(SettingRecord.SCHEME).execute()
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
                        DataVerticle.cloudArchiveSyncRepository = previousSyncs;
                        AutomationsHolder.INSTANCE.autoRecords().automations = previousRules;
                    });
        }
    }
}
