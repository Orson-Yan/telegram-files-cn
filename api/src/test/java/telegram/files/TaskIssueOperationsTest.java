package telegram.files;

import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.Test;
import telegram.files.repository.CloudArchiveRecord;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static telegram.files.ArchiveOperationsTest.await;

class TaskIssueOperationsTest {
    @Test
    void listsEveryIssueInTheTaskEvenBehindAnEarlierPendingMessage() throws Exception {
        try (var f = new ArchiveOperationsTest.Fixture()) {
            f.add(1, "COPY");
            for (long message = 10; message < 17; message++) {
                var record = f.add(message, "COPY"); await(f.records.unknown(record.id(), "DELIVERY_UNCONFIRMED", "Test"));
            }
            await(f.records.enqueue(7, 100, 50, 0, 300, null, "COPY"));
            var other = await(f.records.listRecent(100)).stream().filter(record -> record.targetChatId() == 300).findFirst().orElseThrow();
            await(f.records.unknown(other.id(), "TIMEOUT", "Other target"));
            JsonObject page = await(f.operations.issues(7, 100, 200, 0, 200));
            assertEquals(7L, page.getLong("total")); assertEquals(7, page.getJsonArray("items").size());
            assertEquals("10", page.getJsonArray("items").getJsonObject(0).getString("sourceMessageId"));
        }
    }

    @Test
    void cursorPaginationDoesNotTruncateMoreThanTwoHundredIssues() throws Exception {
        try (var f = new ArchiveOperationsTest.Fixture()) {
            for (long id = 1; id <= 205; id++) await(f.records.enqueue(7, 100, id, 0, 200, null, "COPY"));
            await(f.operations.execute("UPDATE telegram_archive_record SET status = 'UNKNOWN'", Tuple.tuple()));
            var first = await(f.operations.issues(7, 100, 200, 0, 200));
            var second = await(f.operations.issues(7, 100, 200, Long.parseLong(first.getString("nextCursor")), 200));
            assertEquals(200, first.getJsonArray("items").size()); assertEquals(5, second.getJsonArray("items").size());
            assertEquals("201", second.getJsonArray("items").getJsonObject(0).getString("sourceMessageId"));
            assertNull(second.getString("nextCursor")); assertEquals(205L, second.getLong("total"));
        }
    }

    @Test
    void bulkSkipUsesExplicitIdsAndProtectsOtherTasksCompletedAndActiveSends() throws Exception {
        try (var f = new ArchiveOperationsTest.Fixture()) {
            var unknown = f.add(10, "COPY"); var failed = f.add(11, "COPY"); var completed = f.add(12, "COPY"); var sending = f.add(13, "COPY");
            await(f.records.unknown(unknown.id(), "TIMEOUT", "Unknown")); await(f.records.fail(failed.id(), false, 0, "403", "Failure"));
            await(f.records.complete(completed.id(), 9001)); await(f.records.claim(sending.id()));
            await(f.records.enqueue(7, 100, 20, 0, 300, null, "COPY"));
            CloudArchiveRecord other = await(f.records.listRecent(100)).stream().filter(record -> record.targetChatId() == 300).findFirst().orElseThrow();
            await(f.records.unknown(other.id(), "TIMEOUT", "Other"));
            var result = await(f.operations.skipSelected(7, 100, 200, List.of(unknown.id(), failed.id(), completed.id(), sending.id(), other.id(), "missing")));
            assertEquals(2L, result.getLong("skipped"));
            assertEquals("SKIPPED", await(f.operations.find(unknown.id())).status());
            assertEquals("SKIPPED", await(f.operations.find(failed.id())).status());
            assertEquals("COMPLETED", await(f.operations.find(completed.id())).status());
            assertEquals("SENDING", await(f.operations.find(sending.id())).status());
            assertEquals("UNKNOWN", await(f.operations.find(other.id())).status());
            assertEquals(1, await(f.operations.audit(unknown.id())).size());
            assertEquals(0, await(f.operations.audit(other.id())).size());
            assertEquals(0L, await(f.operations.skipSelected(7, 100, 200, List.of(unknown.id(), failed.id()))).getLong("skipped"));
        }
    }

    @Test
    void failedAuditRollsBackSkipAndReportsTheRowAsUnprocessed() throws Exception {
        try (var f = new ArchiveOperationsTest.Fixture()) {
            var record = f.add(10, "COPY"); await(f.records.unknown(record.id(), "TIMEOUT", "Unknown"));
            await(f.operations.execute("DROP TABLE archive_operator_audit", Tuple.tuple()));
            var result = await(f.operations.skipSelected(7, 100, 200, List.of(record.id())));
            assertEquals(0L, result.getLong("skipped"));
            assertEquals("ERROR", result.getJsonArray("results").getJsonObject(0).getString("result"));
            assertEquals("UNKNOWN", await(f.operations.find(record.id())).status());
        }
    }

    @Test
    void taskAndHomepageCountsUseTheSameScopedSuccessLedger() throws Exception {
        try (var f = new ArchiveOperationsTest.Fixture()) {
            var copy = f.add(10, "COPY"); await(f.operations.confirm(f.begin(copy), 9001, System.currentTimeMillis()));
            await(f.records.enqueue(7, 100, 11, 0, 300, null, "FORWARD"));
            var forward = await(f.records.listRecent(100)).stream().filter(record -> record.targetChatId() == 300).findFirst().orElseThrow();
            await(f.records.complete(forward.id(), 9002));
            var unknown = f.add(12, "COPY"); await(f.records.unknown(unknown.id(), "TIMEOUT", "Unknown"));
            var summary = await(f.operations.summary(7, 100L, 200L));
            assertEquals(1L, summary.getLong("completed")); assertEquals(1L, summary.getLong("todayCompleted")); assertEquals(1L, summary.getLong("unconfirmed"));
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var daily = await(f.operations.daily(today, today, ZoneId.of("Asia/Shanghai"), 7, 100L, 200L));
            assertEquals(summary, daily.getJsonObject("statistics"));
            assertEquals(2L, await(f.operations.summary(7, null, null)).getLong("completed"));
        }
    }
    @Test
    void httpRejectsMissingScopeOversizedAndInvalidSelections() throws Exception {
        try (var f = new ArchiveOperationsTest.Fixture()) {
            var record = f.add(10, "COPY"); await(f.records.unknown(record.id(), "TIMEOUT", "Unknown"));
            var routes = new ArchiveOperationsRoutes(f.vertx, () -> org.mockito.Mockito.mock(TelegramHealthMonitor.class));
            var router = io.vertx.ext.web.Router.router(f.vertx);
            router.route().handler(io.vertx.ext.web.handler.BodyHandler.create()); routes.register(router);
            var server = await(f.vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            var client = f.vertx.createHttpClient();
            try {
                var missing = await(client.request(io.vertx.core.http.HttpMethod.GET, server.actualPort(), "127.0.0.1", "/cloud-archive/issues").compose(request -> request.send()));
                assertEquals(400, missing.statusCode()); await(missing.body());
                var scope = JsonObject.of("telegramId", "7", "sourceChatId", "100", "targetChatId", "200");
                for (JsonObject body : List.of(new JsonObject(), scope.copy().put("ids", new io.vertx.core.json.JsonArray()),
                        scope.copy().put("ids", new io.vertx.core.json.JsonArray(java.util.Collections.nCopies(201, record.id()))),
                        scope.copy().put("ids", new io.vertx.core.json.JsonArray().add(123)))) {
                    var response = await(client.request(io.vertx.core.http.HttpMethod.POST, server.actualPort(), "127.0.0.1", "/cloud-archive/issues/skip")
                            .compose(request -> request.putHeader("Content-Type", "application/json").send(body.encode())));
                    assertEquals(400, response.statusCode()); await(response.body());
                }
                assertEquals("UNKNOWN", await(f.operations.find(record.id())).status());
                var response = await(client.request(io.vertx.core.http.HttpMethod.POST, server.actualPort(), "127.0.0.1", "/cloud-archive/issues/skip")
                        .compose(request -> request.putHeader("Content-Type", "application/json").send(scope.put("ids", new io.vertx.core.json.JsonArray().add(record.id())).encode())));
                assertEquals(200, response.statusCode());
                assertEquals(1L, (long) await(response.body()).toJsonObject().getInteger("skipped"));
                assertEquals("SKIPPED", await(f.operations.find(record.id())).status());
            } finally { client.close(); await(server.close()); routes.stop(); }
        }
    }

}
