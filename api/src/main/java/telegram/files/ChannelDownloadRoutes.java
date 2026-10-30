package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.SettingAutoRecords;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Routes are registered behind the application's authentication and CSRF handlers. */
public final class ChannelDownloadRoutes {
    private ChannelDownloadStore store() { return new ChannelDownloadStore(DataVerticle.pool); }
    public void register(Router router) {
        router.get("/channel-downloads").handler(ctx -> Future.all(store().overview(),
                DataVerticle.settingRepository.<telegram.files.repository.SettingTimeLimitedDownload>getByKey(telegram.files.repository.SettingKey.autoDownloadTimeLimited))
            .onSuccess(result -> {
                JsonArray tasks=result.resultAt(0);
                telegram.files.repository.SettingTimeLimitedDownload time=result.resultAt(1);
                tasks.forEach(value -> {
                    JsonObject task=(JsonObject)value;
                    TelegramVerticle telegram=TelegramVerticles.get(task.getString("account_id")).orElse(null);
                    String blocked=telegram==null || !telegram.authorized ? "账号离线，等待重新连接" :
                        !ChannelDownloadVerticle.diskAvailable() ? "磁盘可用空间不足 1 GiB，等待释放空间" :
                        !ChannelDownloadVerticle.allowedTime(time) ? "不在下载时段，等待下一个时段" : null;
                    task.put("blocked_reason",blocked);
                });
                ctx.json(tasks);
            }).onFailure(ctx::fail));
        router.post("/channel-downloads").handler(ctx -> {
            try {
                JsonObject body=ctx.body().asJsonObject();
                var rule=body.getJsonObject("rule",new JsonObject()).mapTo(SettingAutoRecords.DownloadRule.class);
                rule.schemaVersion=1;
                DownloadRules.validate(rule);
                long account=Long.parseLong(body.getString("accountId"));
                TelegramVerticle telegram=TelegramVerticles.get(account).orElseThrow(() -> new IllegalArgumentException("Account not found"));
                LinkedHashSet<String> refs=new LinkedHashSet<>();
                body.getJsonArray("chatIds",new JsonArray()).forEach(id -> refs.add(id.toString()));
                body.getJsonArray("links",new JsonArray()).forEach(link -> { if(!link.toString().isBlank()) refs.add(link.toString().strip()); });
                if(refs.isEmpty() || refs.size()>50) throw new IllegalArgumentException("Select between 1 and 50 channels");
                String destination=body.getString("destination","").strip();
                JsonArray results=new JsonArray();
                Future<Void> work=telegram.wake().timeout(30,TimeUnit.SECONDS);
                for(String ref:refs) work=work.compose(_ -> resolve(telegram,ref).compose(chat -> {
                    JsonObject update=new JsonObject().put("downloadUpdate",JsonObject.mapFrom(rule));
                    if(!destination.isEmpty()) update.put("downloadDestination",destination);
                    return telegram.client.execute(new TdApi.GetChatHistory(chat.id,0,0,1,false)).timeout(30,TimeUnit.SECONDS)
                        .compose(head -> telegram.updateAutoSettings(chat.id,update)
                        .compose(_ -> store().ensure(account,chat.id,Objects.toString(chat.title,String.valueOf(chat.id)),Json.encode(rule),true))
                        .compose(_ -> store().source(account+":"+chat.id))
                        .compose(source -> store().initialize(source,head.messages.length==0?0:head.messages[0].id,rule.downloadHistory)))
                        .compose(_ -> store().action(account+":"+chat.id,"resume"))
                        .onSuccess(_ -> results.add(new JsonObject().put("chatId",String.valueOf(chat.id)).put("title",chat.title).put("created",true)));
                }).recover(error -> {
                    results.add(new JsonObject().put("reference",ref).put("created",false).put("error",error.getMessage()));
                    return Future.succeededFuture();
                }));
                work.onSuccess(_ -> ctx.json(new JsonObject().put("items",results))).onFailure(ctx::fail);
            } catch(RuntimeException error) { ctx.fail(400,error); }
        });
        router.post("/channel-downloads/preview").handler(ctx -> {
            try {
                JsonObject body=ctx.body().asJsonObject();
                var rule=body.getJsonObject("rule").mapTo(SettingAutoRecords.DownloadRule.class);
                DownloadRules.validate(rule);
                long account=Long.parseLong(body.getString("accountId"));
                TelegramVerticle telegram=TelegramVerticles.get(account).orElseThrow();
                resolve(telegram,body.getString("chatId")).compose(chat ->
                    telegram.client.execute(new TdApi.GetChatHistory(chat.id,0,0,30,false)).timeout(30,TimeUnit.SECONDS))
                    .onSuccess(messages -> {
                        JsonArray items=new JsonArray();
                        for(var message:messages.messages) TdApiHelp.getFileHandler(message).ifPresent(handler -> {
                            var file=handler.convertFileRecord(account);
                            items.add(new JsonObject().put("name",file.fileName()).put("type",DownloadRules.type(message))
                                .put("category",DownloadRules.category(file)).put("size",file.size()).put("matched",DownloadRules.matches(message,rule)));
                        });
                        ctx.json(new JsonObject().put("items",items).put("sampleOnly",true));
                    }).onFailure(ctx::fail);
            } catch(RuntimeException error) { ctx.fail(400,error); }
        });
        router.get("/channel-downloads/:id/manifest").handler(ctx -> store().manifest(ctx.pathParam("id"))
            .onSuccess(rows -> ctx.response().putHeader("Content-Type","application/json; charset=utf-8")
                .putHeader("Content-Disposition","attachment; filename=channel-downloads.json").end(rows.encodePrettily())).onFailure(ctx::fail));
        router.get("/channel-downloads/:id/files").handler(ctx -> store().query("""
            SELECT * FROM channel_download_job WHERE source_id=? AND status IN ('FAILED','RETRY') ORDER BY updated_at DESC LIMIT 200
            """,ctx.pathParam("id")).onSuccess(rows -> ctx.json(new JsonArray(ChannelDownloadStore.rows(rows)))).onFailure(ctx::fail));
        router.post("/channel-downloads/:id/:action").handler(ctx -> store().source(ctx.pathParam("id")).compose(source -> {
            if(source==null) return Future.failedFuture(new IllegalArgumentException("Download task not found"));
            Future<Void> enabled=Future.succeededFuture();
            if("resume".equals(ctx.pathParam("action")) && ChannelDownloadStore.n(source,"enabled")==0) {
                TelegramVerticle telegram=TelegramVerticles.get(ChannelDownloadStore.n(source,"account_id")).orElseThrow();
                enabled=telegram.updateAutoSettings(ChannelDownloadStore.n(source,"chat_id"),
                    new JsonObject().put("downloadUpdate",new JsonObject(source.getString("rule_json"))));
            }
            return enabled.compose(_ -> store().action(ctx.pathParam("id"),ctx.pathParam("action")));
        }).onSuccess(_ -> ctx.json(new JsonObject().put("ok",true))).onFailure(ctx::fail));
    }
    static Future<TdApi.Chat> resolve(TelegramVerticle telegram,String reference) {
        if(reference==null || reference.isBlank()) return Future.failedFuture("Channel is required");
        String ref=reference.strip();
        if(ref.matches("-?\\d+")) return telegram.client.execute(new TdApi.GetChat(Long.parseLong(ref))).timeout(30,TimeUnit.SECONDS);
        if(ref.startsWith("https://t.me/") || ref.startsWith("http://t.me/")) {
            String path=java.net.URI.create(ref).getPath();
            String[] pieces=path.split("/");
            if(pieces.length==2) return telegram.client.execute(new TdApi.SearchPublicChat(pieces[1])).timeout(30,TimeUnit.SECONDS);
            return telegram.client.execute(new TdApi.GetMessageLinkInfo(ref)).timeout(30,TimeUnit.SECONDS)
                .compose(info -> telegram.client.execute(new TdApi.GetChat(info.chatId)));
        }
        return telegram.client.execute(new TdApi.SearchPublicChat(ref.replaceFirst("^@",""))).timeout(30,TimeUnit.SECONDS);
    }
}
