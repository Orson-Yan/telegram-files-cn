package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.CloudArchiveRecord;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;

/** Operator endpoints and background maintenance do not depend on an open browser. */
public final class ArchiveOperationsRoutes {
    private final Vertx vertx;
    private final BarkNotificationService notifications;
    private long timer;
    private boolean reconciling;

    public ArchiveOperationsRoutes(Vertx vertx, Supplier<TelegramHealthMonitor> health) {
        this.vertx = vertx;
        notifications = new BarkNotificationService(vertx, health);
    }

    public void register(Router router) {
        router.get("/cloud-archive/daily").handler(this::daily);
        router.get("/cloud-archive/issues").handler(ctx -> DataVerticle.archiveOperations.query("""
                SELECT r.* FROM telegram_archive_record r WHERE r.status IN ('UNKNOWN', 'FAILED')
                    AND NOT EXISTS (SELECT 1 FROM telegram_archive_record p
                        WHERE p.telegram_id = r.telegram_id AND p.source_chat_id = r.source_chat_id
                          AND p.target_chat_id = r.target_chat_id AND p.source_message_id < r.source_message_id
                          AND p.status IN ('STAGED', 'PENDING', 'RETRY', 'SENDING', 'UNKNOWN', 'FAILED'))
                ORDER BY r.updated_at LIMIT 100
                """, Tuple.tuple()).onSuccess(rows -> {
            JsonArray records = new JsonArray();
            rows.forEach(value -> {
                JsonObject row = (JsonObject) value;
                records.add(JsonObject.of("id", row.getString("id"), "telegramId", row.getValue("telegram_id"),
                        "sourceChatId", row.getValue("source_chat_id"), "targetChatId", row.getValue("target_chat_id"),
                        "sourceMessageId", row.getValue("source_message_id"), "status", row.getString("status"),
                        "updatedAt", row.getValue("updated_at"), "errorCode", row.getString("last_error_code")));
            });
            ctx.json(records);
        }).onFailure(ctx::fail));
        router.get("/cloud-archive/records/:recordId/diagnostics").handler(ctx ->
                DataVerticle.archiveOperations.find(ctx.pathParam("recordId")).compose(record -> {
                    if (record == null) return Future.failedFuture("Record not found");
                    return Future.all(DataVerticle.archiveOperations.attempts(record.id()), DataVerticle.archiveOperations.audit(record.id()))
                            .map(result -> JsonObject.of("record", JsonObject.mapFrom(record), "attempts", result.resultAt(0), "audit", result.resultAt(1)));
                }).onSuccess(ctx::json).onFailure(failure -> fail(ctx, failure)));
        router.post("/cloud-archive/records/:recordId/reconcile").handler(ctx ->
                ArchiveDeliveryService.reconcile(ctx.pathParam("recordId")).onSuccess(ctx::json).onFailure(failure -> fail(ctx, failure)));
        router.post("/cloud-archive/records/:recordId/resolve").handler(ctx -> {
            JsonObject body = ctx.body().asJsonObject();
            if (body == null) { ctx.fail(400); return; }
            ArchiveDeliveryService.resolve(ctx.pathParam("recordId"), body)
                    .onSuccess(_ -> { AutoCloudArchiveVerticle.QUEUED_RECORD_AT.remove(ctx.pathParam("recordId")); ctx.json(JsonObject.of("resolved", true)); })
                    .onFailure(failure -> fail(ctx, failure));
        });
        router.get("/notifications/bark").handler(ctx -> notifications.settings().onSuccess(ctx::json).onFailure(ctx::fail));
        router.post("/notifications/bark").handler(ctx -> notifications.save(ctx.body().asJsonObject())
                .onSuccess(ctx::json).onFailure(failure -> fail(ctx, failure)));
        router.post("/notifications/bark/test").handler(ctx -> notifications.test()
                .onSuccess(_ -> ctx.json(JsonObject.of("accepted", true))).onFailure(failure -> fail(ctx, failure)));
    }

    private void daily(RoutingContext ctx) {
        try {
            ZoneId zone = ZoneId.of(ctx.request().getParam("timeZone", "Asia/Shanghai"));
            LocalDate to = LocalDate.parse(ctx.request().getParam("to", LocalDate.now(zone).toString()));
            LocalDate from = LocalDate.parse(ctx.request().getParam("from", to.minusDays(6).toString()));
            long account = Long.parseLong(ctx.request().getParam("telegramId", "0"));
            Long source = ctx.request().getParam("sourceChatId") == null ? null : Long.valueOf(ctx.request().getParam("sourceChatId"));
            Long target = ctx.request().getParam("targetChatId") == null ? null : Long.valueOf(ctx.request().getParam("targetChatId"));
            DataVerticle.archiveOperations.daily(from, to, zone, account, source, target).onSuccess(ctx::json).onFailure(failure -> fail(ctx, failure));
        } catch (RuntimeException invalid) { fail(ctx, new IllegalArgumentException("Invalid date, time zone or filter")); }
    }

    public void start() {
        timer = vertx.setPeriodic(30_000, _ -> {
            if (reconciling) return;
            reconciling = true;
            DataVerticle.archiveOperations.unresolved().compose(records -> {
                Future<Void> work = Future.succeededFuture();
                for (String id : records) work = work.compose(_ -> ArchiveDeliveryService.reconcile(id).recover(_ -> Future.succeededFuture()).mapEmpty());
                return work;
            }).onComplete(_ -> reconciling = false);
        });
        notifications.start();
    }

    public void stop() { if (timer != 0) vertx.cancelTimer(timer); notifications.stop(); }

    private static void fail(RoutingContext ctx, Throwable failure) {
        ctx.response().setStatusCode(400).end(JsonObject.of("error", failure.getMessage()).encode());
    }
}
