package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(VertxExtension.class)
class TelegramHealthMonitorTest {
    private static class TestClock extends Clock {
        long now = 1_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    private static TelegramVerticle account(String id, JsonObject state) {
        var account = mock(TelegramVerticle.class);
        when(account.getId()).thenReturn(id);
        when(account.connectionSnapshot()).thenAnswer(_ -> state.copy());
        return account;
    }
    private static JsonObject state() {
        return JsonObject.of("authorized", true, "sleeping", false, "connectionState", "ready", "proxy", "proxy-a");
    }
    @Test
    void requiresThreeFailuresAndTwoSuccessesWithoutMixingAccounts(Vertx vertx) {
        var clock = new TestClock();
        var monitor = new TelegramHealthMonitor(vertx, clock);
        var first = account("7", state());
        var second = account("8", state());
        when(first.probeConnection()).thenReturn(Future.failedFuture("unreachable"));
        when(second.probeConnection()).thenReturn(Future.succeededFuture(0.12));
        for (int attempt = 0; attempt < 3; attempt++) {
            monitor.check(first); monitor.check(second); clock.now += 30_000;
        }
        assertEquals("ERROR", monitor.snapshot(first).getString("status"));
        assertEquals("HEALTHY", monitor.snapshot(second).getString("status"));
        assertEquals(120, monitor.snapshot(second).getLong("latencyMs"));
        when(first.probeConnection()).thenReturn(Future.succeededFuture(0.2));
        monitor.check(first);
        assertEquals("ERROR", monitor.snapshot(first).getString("status"));
        clock.now += 30_000;
        monitor.check(first);
        assertEquals("HEALTHY", monitor.snapshot(first).getString("status"));
        clock.now += 91_000;
        assertEquals("UNKNOWN", monitor.snapshot(first).getString("status"));
    }
    @Test
    void reachableProxyWithProlongedTelegramDisconnectIsNotShownAsHealthy(Vertx vertx) {
        var clock = new TestClock();
        var monitor = new TelegramHealthMonitor(vertx, clock);
        var state = state().put("connectionState", "connectingToProxy");
        var account = account("7", state);
        when(account.probeConnection()).thenReturn(Future.succeededFuture(0.1));
        monitor.check(account);
        assertEquals("CONNECTING", monitor.snapshot(account).getString("status"));
        clock.now += 60_000;
        monitor.check(account);
        assertEquals("ERROR", monitor.snapshot(account).getString("status"));
        state.put("connectionState", "ready");
        clock.now += 30_000;
        monitor.check(account);
        assertEquals("HEALTHY", monitor.snapshot(account).getString("status"));
    }

    @Test
    void backgroundProbeBypassesTheWakeAndIdleLeaseGateway(Vertx vertx, io.vertx.junit5.VertxTestContext context) throws Exception {
        var raw = new ScriptedTelegramGateway(request -> {
            if (request instanceof org.drinkless.tdlib.TdApi.GetProxies) return new org.drinkless.tdlib.TdApi.AddedProxies(new org.drinkless.tdlib.TdApi.AddedProxy[0]);
            if (request instanceof org.drinkless.tdlib.TdApi.PingProxy) return new org.drinkless.tdlib.TdApi.Seconds(0.12);
            throw new AssertionError("Unexpected request");
        });
        var telegram = new TelegramVerticle("health-fixture", () -> raw);
        telegram.init(vertx, vertx.getOrCreateContext());
        telegram.authorized = true;
        telegram.client = mock(TelegramGateway.class);
        var rawField = TelegramVerticle.class.getDeclaredField("tdlibClient");
        rawField.setAccessible(true); rawField.set(telegram, raw);
        var lifecycle = TelegramVerticle.class.getDeclaredField("clientLifecycle");
        lifecycle.setAccessible(true);
        lifecycle.set(telegram, java.util.Arrays.stream(lifecycle.getType().getEnumConstants()).filter(value -> value.toString().equals("ACTIVE")).findFirst().orElseThrow());
        telegram.probeConnection().onComplete(context.succeeding(seconds -> context.verify(() -> {
            assertEquals(0.12, seconds);
            verifyNoInteractions(telegram.client);
            assertEquals(2, raw.requests().size());
            context.completeNow();
        })));
    }

    @Test
    void sleepingAndInactiveAccountsAreNotProbedAndRouteChangesInvalidateOldResults(Vertx vertx) {
        var clock = new TestClock();
        var monitor = new TelegramHealthMonitor(vertx, clock);
        var state = state();
        var account = account("7", state);
        when(account.probeConnection()).thenReturn(Future.succeededFuture(0.1));
        state.put("sleeping", true);
        monitor.check(account);
        verify(account, never()).probeConnection();
        assertEquals("SLEEPING", monitor.snapshot(account).getString("status"));
        state.put("sleeping", false).put("authorized", false);
        monitor.check(account);
        verify(account, never()).probeConnection();
        assertEquals("INACTIVE", monitor.snapshot(account).getString("status"));
        state.put("authorized", true);
        monitor.check(account);
        assertEquals("HEALTHY", monitor.snapshot(account).getString("status"));
        state.put("proxy", "proxy-b");
        assertEquals("UNKNOWN", monitor.snapshot(account).getString("status"));
        monitor.check(account);
        assertEquals("HEALTHY", monitor.snapshot(account).getString("status"));
    }
}
