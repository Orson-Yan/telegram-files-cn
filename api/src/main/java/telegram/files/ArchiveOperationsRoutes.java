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
    private final ArchiveAutoRecovery recovery;
    private long timer;
    private boolean reconciling;

    public ArchiveOperationsRoutes(Vertx vertx, Supplier<TelegramHealthMonitor> health) {
        this.vertx = vertx;
        notifications = new BarkNotificationService(vertx, health);
        recovery = new ArchiveAutoRecovery(health);
    }

    public void register(Router router) {
        router.get("/cloud-archive/daily").handler(this::daily);
        router.get("/cloud-archive/issues").handler(this::issues);
        router.post("/cloud-archive/issues/skip").handler(this::skipIssues);
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

    private void issues(RoutingContext ctx) {
        try {
            long account = Long.parseLong(ctx.request().getParam("telegramId", "0"));
            long source = Long.parseLong(ctx.request().getParam("sourceChatId", "0"));
            long target = Long.parseLong(ctx.request().getParam("targetChatId", "0"));
            if (account <= 0 || source == 0 || target == 0) throw new IllegalArgumentException("A task scope is required");
            long after = Long.parseLong(ctx.request().getParam("cursor", "0"));
            int limit = Math.max(1, Math.min(200, Integer.parseInt(ctx.request().getParam("limit", "200"))));
            DataVerticle.archiveOperations.issues(account, source, target, after, limit).onSuccess(ctx::json).onFailure(failure -> fail(ctx, failure));
        } catch (RuntimeException invalid) { fail(ctx, new IllegalArgumentException("A valid task scope and cursor are required")); }
    }

    private void skipIssues(RoutingContext ctx) {
        try {
            JsonObject body = ctx.body().asJsonObject();
            if (body == null) throw new IllegalArgumentException();
            long account = Long.parseLong(String.valueOf(body.getValue("telegramId", "0")));
            long source = Long.parseLong(String.valueOf(body.getValue("sourceChatId", "0")));
            long target = Long.parseLong(String.valueOf(body.getValue("targetChatId", "0")));
            JsonArray selected = body.getJsonArray("ids");
            if (account <= 0 || source == 0 || target == 0 || selected == null || selected.isEmpty() || selected.size() > 200)
                throw new IllegalArgumentException();
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            for (Object value : selected) {
                if (!(value instanceof String id) || id.isBlank() || id.length() > 64) throw new IllegalArgumentException();
                ids.add(id);
            }
            DataVerticle.archiveOperations.skipSelected(account, source, target, ids.stream().toList())
                    .onSuccess(ctx::json).onFailure(failure -> fail(ctx, failure));
        } catch (RuntimeException invalid) { fail(ctx, new IllegalArgumentException("Select 1 to 200 issues from one task")); }
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
            recovery.scan();
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
