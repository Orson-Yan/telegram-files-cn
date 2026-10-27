package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.TelegramRecord;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class ChannelDownloadEngineTest {
    @Test void backfillLivePauseAndRestartUseOneDurableQueue(Vertx vertx,VertxTestContext context) {
        Map<Long,TdApi.Message> messages=new ConcurrentHashMap<>();
        for(long id:new long[]{100,200,300,400}) {
            TdApi.Message message=DownloadRulesTest.document("lesson-"+id+".mp4","");message.chatId=555;message.id=id;
            ((TdApi.MessageDocument)message.content).document.document.remote.uniqueId="engine-"+id;
            messages.put(id,message);
        }
        Map<Long,Integer> starts=new ConcurrentHashMap<>();
        AtomicLong head=new AtomicLong(200);
        ScriptedTelegramGateway gateway=new ScriptedTelegramGateway(request -> {
            if(request instanceof TdApi.GetChatHistory) return new TdApi.Messages(1,new TdApi.Message[]{messages.get(head.get())});
            if(request instanceof TdApi.SearchChatMessages search) return search.fromMessageId==0
                ?new TdApi.FoundChatMessages(4,messages.values().stream().filter(m -> m.id<=head.get()).sorted((a,b) -> Long.compare(b.id,a.id)).toArray(TdApi.Message[]::new),99)
                :new TdApi.FoundChatMessages(0,new TdApi.Message[0],0);
            if(request instanceof TdApi.GetMessage get) return messages.get(get.messageId);
            return new TdApi.Ok();
        });
        TelegramVerticle telegram=new TelegramVerticle("fixture",() -> gateway) {
            @Override public Future<Void> wake() { return Future.succeededFuture(); }
            @Override public void refreshIdlePolicy() { }
            @Override public Future<FileRecord> startDownload(Long chat,Long message,Integer file) {
                starts.merge(message,1,Integer::sum);
                FileRecord record=TdApiHelp.getFileHandler(messages.get(message)).orElseThrow().convertFileRecord(997);
                return DataVerticle.fileRepository.getByUniqueId(record.uniqueId()).compose(existing -> {
                        if(existing!=null) assertTrue(existing.isDownloadStatus(FileRecord.DownloadStatus.idle),"Missing completed files must be released before restarting");
                        return DataVerticle.fileRepository.createIfNotExist(record);
                    })
                    .compose(_ -> DataVerticle.fileRepository.updateDownloadStatus(record.id(),record.uniqueId(),"fixture",FileRecord.DownloadStatus.completed,System.currentTimeMillis()))
                    .compose(_ -> DataVerticle.fileRepository.getByUniqueId(record.uniqueId()));
            }
        };
        telegram.telegramRecord=new TelegramRecord(997,"fixture","fixture",null);telegram.authorized=true;telegram.client=gateway;
        var automation=new SettingAutoRecords.Automation();automation.telegramId=997;automation.chatId=555;
        automation.download.enabled=true;automation.download.rule.downloadHistory=true;automation.download.rule.schemaVersion=1;
        AtomicReference<String> engine=new AtomicReference<>();
        vertx.deployVerticle(new DataVerticle()).compose(database -> {
            TelegramVerticles.add(telegram);AutomationsHolder.INSTANCE.autoRecords().add(automation);
            ChannelDownloadStore store=new ChannelDownloadStore(DataVerticle.pool);
            return DataVerticle.settingRepository.createOrUpdate(SettingKey.autoDownloadLimit.name(),"5")
                .compose(_ -> DataVerticle.settingRepository.createOrUpdate(SettingKey.autoDownloadTimeLimited.name(),"{\"startTime\":\"00:00\",\"endTime\":\"00:00\"}"))
                .compose(_ -> DataVerticle.fileRepository.createIfNotExist(TdApiHelp.getFileHandler(messages.get(100L)).orElseThrow().convertFileRecord(997)))
                .compose(_ -> DataVerticle.fileRepository.updateDownloadStatus(42,"engine-100","missing-fixture-file",FileRecord.DownloadStatus.completed,System.currentTimeMillis()))
                .compose(_ -> vertx.deployVerticle(new ChannelDownloadVerticle()))
                .compose(deployment -> { engine.set(deployment);return until(vertx,() -> store.query("SELECT COUNT(*) AS total FROM channel_download_job WHERE account_id=997 AND status='COMPLETED'")
                    .map(rows -> ((Number)rows.iterator().next().getValue("total")).intValue()==2)); })
                .compose(_ -> vertx.undeploy(engine.get()))
                .compose(_ -> vertx.deployVerticle(new ChannelDownloadVerticle()))
                .compose(deployment -> { engine.set(deployment);return store.action("997:555","pause"); })
                .compose(_ -> {
                    assertEquals(Map.of(100L,1,200L,1),starts);
                    head.set(300);
                    vertx.eventBus().publish(EventEnum.MESSAGE_RECEIVED.address(),new JsonObject().put("telegramId",997L).put("chatId",555L).put("messageId",300L));
                    return delay(vertx,1200);
                }).compose(_ -> {
                    assertFalse(starts.containsKey(300L));return store.action("997:555","resume");
                }).compose(_ -> {
                    head.set(400);
                    vertx.eventBus().publish(EventEnum.MESSAGE_RECEIVED.address(),new JsonObject().put("telegramId",997L).put("chatId",555L).put("messageId",400L));
                    return until(vertx,() -> store.query("SELECT COUNT(*) AS total FROM channel_download_job WHERE account_id=997 AND status='COMPLETED'")
                        .map(rows -> ((Number)rows.iterator().next().getValue("total")).intValue()==4));
                }).compose(_ -> {
                    assertEquals(1,starts.get(400L));assertEquals(1,starts.get(300L));assertEquals(1,starts.get(100L));assertEquals(1,starts.get(200L));
                    return Future.succeededFuture();
                }).recover(error -> store.overview().compose(overview -> store.query("SELECT message_id,status,last_error FROM channel_download_job WHERE account_id=997")
                    .compose(rows -> Future.failedFuture("Engine test: "+error.getMessage()+"; sources="+overview.encode()+"; jobs="+ChannelDownloadStore.rows(rows)+"; starts="+starts))))
                .eventually(() -> {
                    TelegramVerticles.remove(telegram);AutomationsHolder.INSTANCE.autoRecords().remove(997,555);
                    return (engine.get()==null?Future.<Void>succeededFuture():vertx.undeploy(engine.get()))
                        .compose(_ -> vertx.undeploy(database)).compose(_ -> DataVerticleTest.clear(vertx));
                });
        }).onComplete(context.succeeding(_ -> context.completeNow()));
    }
    @Test void floodWaitStopsTheRestOfTheAccountBatchAndSurvivesRestart(Vertx vertx,VertxTestContext context) {
        AtomicInteger starts=new AtomicInteger();
        Map<Long,TdApi.Message> messages=new ConcurrentHashMap<>();
        for(long chat:new long[]{555,556}) {
            TdApi.Message message=DownloadRulesTest.document("lesson.mp4","");message.chatId=chat;
            ((TdApi.MessageDocument)message.content).document.document.remote.uniqueId="flood-"+chat;
            messages.put(chat,message);
        }
        ScriptedTelegramGateway gateway=new ScriptedTelegramGateway(request -> {
            if(request instanceof TdApi.GetChatHistory) return new TdApi.Messages(0,new TdApi.Message[0]);
            if(request instanceof TdApi.GetMessage get) return messages.get(get.chatId);
            return new TdApi.Ok();
        });
        TelegramVerticle telegram=new TelegramVerticle("fixture",() -> gateway) {
            @Override public void refreshIdlePolicy() { }
            @Override public Future<FileRecord> startDownload(Long chat,Long message,Integer file) {
                starts.incrementAndGet();return Future.failedFuture(new TelegramRunException(new TdApi.Error(429,"FLOOD_WAIT_60")));
            }
        };
        telegram.telegramRecord=new TelegramRecord(998,"fixture","fixture",null);telegram.authorized=true;telegram.client=gateway;
        AtomicReference<String> engine=new AtomicReference<>();
        vertx.deployVerticle(new DataVerticle()).compose(database -> {
            TelegramVerticles.add(telegram);
            ChannelDownloadStore store=new ChannelDownloadStore(DataVerticle.pool);
            Future<Void> setup=DataVerticle.settingRepository.createOrUpdate(SettingKey.autoDownloadLimit.name(),"5").mapEmpty();
            for(long chat:new long[]{555,556}) {
                var auto=new SettingAutoRecords.Automation();auto.telegramId=998;auto.chatId=chat;auto.download.enabled=true;
                AutomationsHolder.INSTANCE.autoRecords().add(auto);
                setup=setup.compose(_ -> store.ensure(998,chat,"fixture",io.vertx.core.json.Json.encode(auto.download.rule),true))
                    .compose(_ -> store.source("998:"+chat)).compose(source -> store.initialize(source,200,false))
                    .compose(_ -> store.source("998:"+chat)).compose(source -> store.candidates(source,java.util.List.of(TdApiHelp.getFileHandler(messages.get(chat)).orElseThrow().convertFileRecord(998)),true));
            }
            return setup.compose(_ -> vertx.deployVerticle(new ChannelDownloadVerticle()))
                .compose(deployment -> {engine.set(deployment);return until(vertx,() -> Future.succeededFuture(starts.get()>0));})
                .compose(_ -> delay(vertx,1200)).compose(_ -> {
                    assertEquals(1,starts.get(),"A flood wait must stop the remaining batch, including other channels");
                    return vertx.undeploy(engine.get());
                }).compose(_ -> vertx.deployVerticle(new ChannelDownloadVerticle()))
                .compose(deployment -> {engine.set(deployment);return delay(vertx,1200);})
                .compose(_ -> {assertEquals(1,starts.get(),"Persisted account cooldown must survive restart");return Future.succeededFuture();})
                .eventually(() -> {
                    TelegramVerticles.remove(telegram);
                    AutomationsHolder.INSTANCE.autoRecords().remove(998,555);AutomationsHolder.INSTANCE.autoRecords().remove(998,556);
                    return (engine.get()==null?Future.<Void>succeededFuture():vertx.undeploy(engine.get()))
                        .compose(_ -> vertx.undeploy(database)).compose(_ -> DataVerticleTest.clear(vertx));
                });
        }).onComplete(context.succeeding(_ -> context.completeNow()));
    }
    private Future<Void> until(Vertx vertx,Supplier<Future<Boolean>> condition) {
        Promise<Void> done=Promise.promise();
        long deadline=System.currentTimeMillis()+15000;
        long timer=vertx.setPeriodic(100,id -> condition.get().onComplete(result -> {
            if(done.future().isComplete()) return;
            if(result.failed()) { vertx.cancelTimer(id);done.fail(result.cause()); }
            else if(result.result()) { vertx.cancelTimer(id);done.complete(); }
            else if(System.currentTimeMillis()>deadline) { vertx.cancelTimer(id);done.fail("Timed out waiting for durable engine"); }
        }));
        return done.future().eventually(() -> {vertx.cancelTimer(timer);return Future.succeededFuture();});
    }
    private Future<Void> delay(Vertx vertx,long ms) { Promise<Void> done=Promise.promise();vertx.setTimer(ms,_ -> done.complete());return done.future(); }
}
