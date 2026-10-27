package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class ChannelDownloadStoreTest {
    private FileRecord file(long message) {
        return new FileRecord(42,"persisted-file",991,100,message,0,1700000000,false,2048,0,
            "file","application/zip","file.zip",null,null,"caption",null,null,"idle","idle",0,null,null,0,0,0);
    }
    @Test void ruleChangesRejectOldInFlightPages(Vertx vertx,VertxTestContext context) {
        vertx.deployVerticle(new DataVerticle()).compose(deployment -> {
            ChannelDownloadStore store=new ChannelDownloadStore(DataVerticle.pool);
            var rule=new SettingAutoRecords.DownloadRule();rule.downloadHistory=true;
            return store.query("DELETE FROM channel_download_job WHERE account_id=992")
                .compose(_ -> store.query("DELETE FROM channel_download_source WHERE account_id=992"))
                .compose(_ -> store.ensure(992,100,"rule-change",Json.encode(rule),true)).compose(_ -> store.source("992:100"))
                .compose(old -> {
                    rule.query="changed";
                    return store.ensure(992,100,"rule-change",Json.encode(rule),true)
                        .compose(_ -> store.page(old,List.of(file(300)),false,299,0,true,100));
                }).compose(_ -> store.source("992:100")).compose(current -> {
                    assertEquals(2,ChannelDownloadStore.n(current,"rule_version"));
                    assertEquals(0,ChannelDownloadStore.n(current,"history_cursor"));
                    return store.due(992,10);
                }).compose(jobs -> { assertTrue(jobs.isEmpty());return Future.succeededFuture(); })
                .eventually(() -> vertx.undeploy(deployment).compose(_ -> DataVerticleTest.clear(vertx)));
        }).onComplete(context.succeeding(_ -> context.completeNow()));
    }
    @Test void checkpointsAndJobsSurviveRepositoryRestartAndPauseCannotAdvanceDiscovery(Vertx vertx,VertxTestContext context) {
        vertx.deployVerticle(new DataVerticle()).compose(deployment -> {
            ChannelDownloadStore store=new ChannelDownloadStore(DataVerticle.pool);
            String id="991:100";
            var rule=new SettingAutoRecords.DownloadRule();rule.downloadHistory=true;
            return store.query("DELETE FROM channel_download_job WHERE account_id=991")
                .compose(_ -> store.query("DELETE FROM channel_download_source WHERE account_id=991"))
                .compose(_ -> store.ensure(991,100,"fixture",Json.encode(rule),true))
                .compose(_ -> store.source(id))
                .compose(source -> store.initialize(source,500,true))
                .compose(_ -> store.source(id))
                .compose(source -> store.page(source,List.of(file(200)),false,199,0,false,100))
                .compose(_ -> new ChannelDownloadStore(DataVerticle.pool).source(id))
                .compose(source -> {
                    assertEquals(199,ChannelDownloadStore.n(source,"history_cursor"));
                    return store.due(991,5);
                }).compose(jobs -> {
                    assertEquals(1,jobs.size());
                    return store.action(id,"pause");
                }).compose(_ -> store.due(991,5)).compose(jobs -> {
                    assertTrue(jobs.isEmpty());
                    return store.source(id);
                }).compose(source -> store.page(source,List.of(file(100)),false,99,0,true,100))
                .compose(_ -> store.source(id)).compose(source -> {
                    assertEquals(199,ChannelDownloadStore.n(source,"history_cursor"));
                    return store.action(id,"resume");
                }).compose(_ -> store.source(id))
                .compose(source -> store.page(source,List.of(file(200)),false,199,0,false,100))
                .compose(_ -> store.source(id))
                .compose(source -> store.candidates(source,List.of(file(300)),true))
                .compose(_ -> store.due(991,5)).compose(jobs -> {
                    assertEquals(2,jobs.size());
                    assertEquals(300,ChannelDownloadStore.n(jobs.getFirst(),"message_id"));
                    return store.due(991,5,true);
                }).compose(jobs -> {
                    assertEquals(200,ChannelDownloadStore.n(jobs.getFirst(),"message_id"),"History must retain dispatch opportunities under live traffic");
                    return store.claim(jobs.getFirst()).compose(first -> {
                        assertTrue(first);
                        return store.claim(jobs.getFirst());
                    });
                }).compose(second -> { assertFalse(second);return store.overview(); })
                .compose(overview -> { assertFalse(overview.isEmpty());return Future.succeededFuture(); })
                .eventually(() -> vertx.undeploy(deployment).compose(_ -> DataVerticleTest.clear(vertx)));
        }).onComplete(context.succeeding(_ -> context.completeNow()));
    }
}
