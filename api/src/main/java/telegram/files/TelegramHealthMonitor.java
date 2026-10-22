package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side probes continue without an open browser and never wake sleeping accounts. */
public final class TelegramHealthMonitor {
    private final Clock clock;
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    private final Vertx vertx;
    private long timer;

    public TelegramHealthMonitor(Vertx vertx) { this(vertx, Clock.systemUTC()); }
    TelegramHealthMonitor(Vertx vertx, Clock clock) { this.vertx = vertx; this.clock = clock; }

    public void start() {
        timer = vertx.setPeriodic(0, 30_000, _ -> {
            var accounts = java.util.List.copyOf(TelegramVerticles.getAll());
            probes.keySet().removeIf(id -> accounts.stream().noneMatch(account -> String.valueOf(account.getId()).equals(id)));
            accounts.forEach(this::check);
        });
    }

    public void stop() { if (timer != 0) vertx.cancelTimer(timer); }

    public synchronized Future<Void> check(TelegramVerticle telegram) {
        JsonObject current = telegram.connectionSnapshot();
        String id = String.valueOf(telegram.getId());
        Probe probe = probes.computeIfAbsent(id, _ -> new Probe());
        String route = current.getString("proxy", "");
        if (!java.util.Objects.equals(route, probe.route)) {
            probe = new Probe(); probe.route = route; probes.put(id, probe);
        }
        if (probe.pending != null && !probe.pending.isComplete()) return probe.pending;
        if (current.getBoolean("sleeping", false) || !current.getBoolean("authorized", false)) {
            probe.checkedAt = 0; probe.failures = 0; probe.successes = 0; probe.unhealthy = false;
            return Future.succeededFuture();
        }
        if (probe.checkedAt > 0 && clock.millis() - probe.checkedAt < 5_000) return Future.succeededFuture();
        if ("ready".equals(current.getString("connectionState"))) probe.disconnectedSince = 0;
        else if (probe.disconnectedSince == 0) probe.disconnectedSince = clock.millis();
        Probe target = probe;
        // A bounded request runs through the account's actual proxy (or direct connection).
        var bounded = io.vertx.core.Promise.<Double>promise();
        long timeout = vertx.setTimer(8_000, _ -> bounded.tryFail("Connection probe timed out"));
        try {
            telegram.probeConnection().onComplete(result -> {
                if (result.succeeded()) bounded.tryComplete(result.result());
                else bounded.tryFail(result.cause());
            });
        } catch (Exception failure) { bounded.tryFail(failure); }
        probe.pending = bounded.future().onSuccess(seconds -> target.success(clock.millis(), seconds))
                .onFailure(_ -> target.failure(clock.millis())).onComplete(_ -> vertx.cancelTimer(timeout))
                .recover(_ -> Future.succeededFuture(0.0)).mapEmpty();
        return probe.pending;
    }

    public synchronized JsonObject snapshot(TelegramVerticle telegram) {
        JsonObject result = telegram.connectionSnapshot();
        Probe probe = probes.get(String.valueOf(telegram.getId()));
        if (probe != null && !java.util.Objects.equals(probe.route, result.getString("proxy", ""))) probe = null;
        long now = clock.millis();
        String status;
        if (result.getBoolean("sleeping", false)) status = "SLEEPING";
        else if (!result.getBoolean("authorized", false)) status = "INACTIVE";
        else if (probe == null || probe.checkedAt == 0 || now - probe.checkedAt > 90_000) status = "UNKNOWN";
        else if (probe.unhealthy) status = "ERROR";
        else if (!"ready".equals(result.getString("connectionState")) && probe.disconnectedSince > 0
                && now - probe.disconnectedSince >= 60_000) status = "ERROR";
        else if (probe.failures > 0 || !"ready".equals(result.getString("connectionState"))) status = "CONNECTING";
        else status = "HEALTHY";
        return result.put("status", status).put("checkedAt", probe == null ? 0 : probe.checkedAt)
                .put("lastSuccessAt", probe == null ? 0 : probe.lastSuccessAt)
                .put("unhealthySince", probe == null ? 0 : probe.unhealthySince > 0 ? probe.unhealthySince : probe.disconnectedSince)
                .put("latencyMs", probe == null || probe.checkedAt == 0 || probe.failures > 0 ? null : Math.round(probe.seconds * 1000))
                .put("failureCount", probe == null ? 0 : probe.failures)
                .put("probeStatus", probe == null || probe.checkedAt == 0 ? "UNKNOWN" : probe.failures > 0 ? "FAILED" : "OK");
    }

    public JsonArray snapshots() {
        return new JsonArray(TelegramVerticles.getAll().stream().map(this::snapshot).toList());
    }

    static final class Probe {
        String route;
        volatile long checkedAt, lastSuccessAt, unhealthySince, disconnectedSince;
        volatile int failures, successes;
        volatile boolean unhealthy;
        volatile double seconds;
        Future<Void> pending;
        synchronized void success(long now, double latency) {
            checkedAt = now; lastSuccessAt = now; seconds = latency; failures = 0;
            if (++successes >= 2 || !unhealthy) { unhealthy = false; unhealthySince = 0; }
        }
        synchronized void failure(long now) {
            checkedAt = now; successes = 0;
            if (failures++ == 0) unhealthySince = now;
            if (failures >= 3) unhealthy = true;
        }
    }
}
