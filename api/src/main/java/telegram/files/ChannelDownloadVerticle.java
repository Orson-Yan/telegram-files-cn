package telegram.files;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.SettingTimeLimitedDownload;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static telegram.files.ChannelDownloadStore.n;

/** Bounded, durable channel downloads; history and live reconciliation have independent cursors. */
public final class ChannelDownloadVerticle extends AbstractVerticle {
    private ChannelDownloadStore store;
    private long timer;
    private boolean ticking;
    private final Set<Long> dispatching = new HashSet<>();
    private final Map<Long,String> lastSource = new HashMap<>();
    private final Map<String,Integer> scanTurns = new HashMap<>();
    private final Map<Long,Long> accountCooldowns = new HashMap<>();
    private final Map<Long,Integer> dispatchTurns = new HashMap<>();
    private String lastAutoFingerprint;
    private long lastSyncTimestamp;
    private long lastReconcileCompletedTime;
    private long lastPausedReconcile;
    private boolean reconcilingCompleted;
    private Integer cachedLimit;
    private SettingTimeLimitedDownload cachedTime;
    private long settingsLoadedAt;
    private final Map<Long,Long> wakeFailureLogAt = new HashMap<>();

    @Override public void start(Promise<Void> promise) {
        store = new ChannelDownloadStore(DataVerticle.pool);
        lastReconcileCompletedTime = System.currentTimeMillis();
        lastPausedReconcile = System.currentTimeMillis();
        reconcilingCompleted = true;
        store.reconcileCompletedJobs()
            .onSuccess(count -> {
                if (count > 0) cn.hutool.log.LogFactory.get().info("Channel download startup reconciled %d completed jobs".formatted(count));
            })
            .onFailure(error -> log("Channel download startup reconciliation failed", error))
            .onComplete(_ -> reconcilingCompleted = false);
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.autoDownloadLimit.name()), _ -> settingsLoadedAt = 0);
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.autoDownloadTimeLimited.name()), _ -> settingsLoadedAt = 0);
        vertx.eventBus().consumer(EventEnum.MESSAGE_RECEIVED.address(), event -> {
            JsonObject body = (JsonObject)event.body();
            String id = body.getLong("telegramId")+":"+body.getLong("chatId");
            store.source(id).compose(source -> {
                if (!active(source) || n(source,"initialized")==0) return Future.succeededFuture();
                TelegramVerticle telegram = TelegramVerticles.get(n(source,"account_id")).orElse(null);
                if (telegram == null || !telegram.authorized) return Future.succeededFuture();
                return telegram.client.execute(new TdApi.GetMessage(body.getLong("chatId"),body.getLong("messageId")))
                    .timeout(30,TimeUnit.SECONDS).compose(message -> store.candidates(source, matched(source,List.of(message)),true));
            }).onFailure(error -> log("Live candidate deferred to reconciliation",error));
        });
        timer = vertx.setPeriodic(1000,_ -> tick());
        promise.complete();
    }
    @Override public void stop() { if (timer!=0) vertx.cancelTimer(timer); }

    private void tick() {
        if (ticking) return;
        ticking=true;
        sync().compose(_ -> store.running()).compose(active ->
                reconcile(active).compose(_ -> ensureSettings()).compose(_ -> store.sources())
                    .compose(sources -> scanSources(sources, active)))
            .onFailure(error -> log("Channel download tick failed",error))
            .onComplete(_ -> ticking=false);
    }
    private Future<Void> scanSources(List<JsonObject> sources, List<JsonObject> activeJobs) {
        Future<Void> scans=Future.succeededFuture();
        Map<Long,List<JsonObject>> byAccount=new LinkedHashMap<>();
        for (JsonObject source:sources) {
            if(isFloodWait(source.getString("last_error",""))) accountCooldowns.merge(n(source,"account_id"),n(source,"next_scan_at"),Math::max);
            if(active(source)) byAccount.computeIfAbsent(n(source,"account_id"),_ -> new ArrayList<>()).add(source);
        }
        for (var account:byAccount.entrySet()) {
            long accountId=account.getKey();
            TelegramVerticle telegram=TelegramVerticles.get(accountId).orElse(null);
            if(telegram==null) continue;
            if(!telegram.authorized) {
                if(telegram.isSleepingOrWaking()) wakeAccount(telegram,accountId,"Failed to wake account for channel download");
                continue;
            }
            if(!diskAvailable()) continue;
            if(accountCooldowns.getOrDefault(accountId,0L)>System.currentTimeMillis()) continue;
            List<JsonObject> candidates=account.getValue();
            if(candidates.stream().anyMatch(s -> n(s,"next_scan_at")>System.currentTimeMillis()
                && isFloodWait(s.getString("last_error","")))) continue;
            JsonObject source=candidates.stream().filter(s -> n(s,"next_scan_at")<=System.currentTimeMillis())
                .filter(s -> !s.getString("id").equals(lastSource.get(accountId)) || candidates.size()==1)
                .findFirst().orElse(null);
            if(source!=null) {
                lastSource.put(accountId,source.getString("id"));
                scans=scans.compose(_ -> scan(telegram,source).recover(error -> {
                    if(isFloodWait(Objects.toString(error.getMessage(),""))) accountCooldowns.put(accountId,System.currentTimeMillis()+retryDelay(error,1));
                    return store.error(source.getString("id"),n(source,"rule_version"),
                    error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(),
                    System.currentTimeMillis()+retryDelay(error,1));
                }));
            }
            dispatch(telegram,accountId,activeJobs);
        }
        return scans.compose(_ -> scanComments());
    }
    private Future<Void> sync() {
        var autos=AutomationsHolder.INSTANCE.autoRecords().getDownloadEnabledItems();
        StringBuilder fp = new StringBuilder();
        for (var auto : autos) {
            fp.append(auto.uniqueKey()).append(':').append(auto.download.rule == null ? 0 : auto.download.rule.hashCode()).append(';');
        }
        String currentFingerprint = fp.toString();
        long now = System.currentTimeMillis();
        if (Objects.equals(currentFingerprint, lastAutoFingerprint) && now - lastSyncTimestamp < 30_000L) {
            return Future.succeededFuture();
        }
        lastAutoFingerprint = currentFingerprint;
        lastSyncTimestamp = now;

        return store.sources().compose(existing -> {
            Set<String> enabled=new HashSet<>();
            Future<Void> work=Future.succeededFuture();
            for(var auto:autos) {
                enabled.add(auto.uniqueKey());
                TelegramVerticle telegram=TelegramVerticles.get(auto.telegramId).orElse(null);
                TdApi.Chat chat=telegram==null?null:telegram.getChat(auto.chatId);
                String title=chat==null?String.valueOf(auto.chatId):Objects.toString(chat.title,String.valueOf(auto.chatId));
                work=work.compose(_ -> store.ensure(auto.telegramId,auto.chatId,title,Json.encode(auto.download.rule),true));
            }
            for(JsonObject source:existing) if(!enabled.contains(source.getString("id")) && n(source,"enabled")!=0)
                work=work.compose(_ -> store.query("UPDATE channel_download_source SET enabled=0 WHERE id=?",source.getString("id")).mapEmpty());
            return work;
        });
    }
    private Future<Void> scan(TelegramVerticle telegram,JsonObject source) {
        if(n(source,"initialized")==0) return telegram.client.execute(new TdApi.GetChatHistory(n(source,"chat_id"),0,0,1,false))
            .timeout(30,TimeUnit.SECONDS).compose(messages -> store.initialize(source,
                messages.messages.length==0?0:messages.messages[0].id,rule(source).downloadHistory));
        boolean live=n(source,"history_done")!=0 || n(source,"live_target")!=0
            || System.currentTimeMillis()-n(source,"last_check")>30_000;
        // Alternate history and catch-up pages while the historical backfill is running.
        if(n(source,"history_done")==0 && n(source,"live_target")==0)
            live=scanTurns.merge(source.getString("id"),1,Integer::sum)%4==0;
        // A negative target requests an immediate head refresh after a control operation.
        if(live && n(source,"live_target")<=0) {
            if(n(source,"live_target")==0 && n(source,"history_done")!=0 && System.currentTimeMillis()-n(source,"last_check")<30_000)
                return Future.succeededFuture();
            return telegram.client.execute(new TdApi.GetChatHistory(n(source,"chat_id"),0,0,1,false))
                .timeout(30,TimeUnit.SECONDS).compose(messages -> {
                    long head=Math.max(n(source,"live_watermark"),messages.messages.length==0?0:messages.messages[0].id);
                    if(head<=n(source,"live_watermark")) return store.page(source,List.of(),true,0,head,true,0);
                    return scanPage(telegram,source,true,head);
                });
        }
        return scanPage(telegram,source,live,n(source,"live_target"));
    }
    private Future<Void> scanPage(TelegramVerticle telegram,JsonObject source,boolean live,long target) {
        TdApi.SearchChatMessages search=new TdApi.SearchChatMessages();
        search.chatId=n(source,"chat_id");
        search.query="";
        search.fromMessageId=n(source,live?"live_cursor":"history_cursor");
        search.limit=100;
        return telegram.client.execute(search).timeout(30,TimeUnit.SECONDS).compose(page -> {
            List<TdApi.Message> messages=Arrays.asList(page.messages);
            boolean done=page.nextFromMessageId==0;
            if(live && messages.stream().anyMatch(m -> m.id<=n(source,"live_watermark"))) done=true;
            long upper=live?target:n(source,"boundary_id");
            List<TdApi.Message> bounded=messages.stream().filter(m -> m.id<=upper)
                .filter(m -> !live || m.id>n(source,"live_watermark")).toList();
            if(!done && page.nextFromMessageId==search.fromMessageId)
                return Future.failedFuture("History cursor did not advance; retrying without skipping messages");
            return store.page(source,matched(source,bounded),live,page.nextFromMessageId,target,done,messages.size());
        });
    }
    static List<FileRecord> matched(JsonObject source,List<TdApi.Message> messages) {
        var rule=rule(source);
        return messages.stream().filter(message -> DownloadRules.matches(message,rule))
            .map(message -> TdApiHelp.getFileHandler(message).orElseThrow().convertFileRecord(n(source,"account_id"))).toList();
    }
    private Future<Void> ensureSettings() {
        long now = System.currentTimeMillis();
        if (cachedLimit != null && now - settingsLoadedAt < 30_000L) return Future.succeededFuture();
        return Future.all(DataVerticle.settingRepository.<Integer>getByKey(SettingKey.autoDownloadLimit),
                DataVerticle.settingRepository.<SettingTimeLimitedDownload>getByKey(SettingKey.autoDownloadTimeLimited))
            .map(settings -> {
                cachedLimit = settings.resultAt(0);
                cachedTime = settings.resultAt(1);
                settingsLoadedAt = System.currentTimeMillis();
                return (Void) null;
            });
    }
    private void dispatch(TelegramVerticle telegram,long account,List<JsonObject> jobsInFlight) {
        if(!dispatching.add(account)) return;
        Future<Void> work = !allowedTime(cachedTime) || !diskAvailable()
            ? Future.succeededFuture()
            : store.due(account,100,dispatchTurns.merge(account,1,Integer::sum)%4==0).compose(jobs -> {
                if(jobs.isEmpty()) return Future.succeededFuture();
                int limit=Math.max(1,Math.min(10,cachedLimit==null?5:cachedLimit));
                return DataVerticle.fileRepository.getByDownloadStatus(account,FileRecord.DownloadStatus.downloading)
                    .compose(nativeDownloads -> {
                        int available=Math.max(0,limit-occupiedSlots(account,nativeDownloads,jobsInFlight));
                        if(available==0) return Future.succeededFuture();
                        // Round-robin channels within the account; prioritize live files within each turn.
                        LinkedHashMap<String,JsonObject> selected=new LinkedHashMap<>();
                        for(JsonObject job:jobs) selected.putIfAbsent(job.getString("source_id"),job);
                        List<JsonObject> picks=new ArrayList<>(selected.values());
                        for(JsonObject job:jobs) if(picks.size()<available && !picks.contains(job)) picks.add(job);
                        Future<Void> starts=Future.succeededFuture();
                        long batchReserved = 0;
                        for(JsonObject job:picks.subList(0,Math.min(available,picks.size()))) {
                            long fileSize = Math.max(0, n(job, "file_size"));
                            batchReserved += fileSize;
                            long required = batchReserved;
                            starts = starts.compose(_ -> start(telegram, job, required));
                        }
                        return starts;
                    });
            });
        work.onFailure(error -> log("Download dispatch failed",error)).onComplete(_ -> dispatching.remove(account));
    }
    private Future<Void> start(TelegramVerticle telegram,JsonObject job,long required) {
        if(accountCooldowns.getOrDefault(n(job,"account_id"),0L)>System.currentTimeMillis()) return Future.succeededFuture();
        return store.claim(job).compose(claimed -> {
            if(!claimed) return Future.succeededFuture();
            return telegram.client.execute(new TdApi.GetMessage(n(job,"chat_id"),n(job,"message_id")))
                .timeout(30,TimeUnit.SECONDS).compose(message -> store.source(job.getString("source_id")).compose(source -> {
                    var configured=AutomationsHolder.INSTANCE.autoRecords().getItem(n(job,"account_id"),n(source,"chat_id"));
                    if(!active(source) || configured==null || !configured.download.enabled
                        || !Json.encode(configured.download.rule).equals(source.getString("rule_json"))
                        || n(source,"rule_version")!=n(job,"rule_version"))
                        return store.finish(job.getString("id"),"RETRY","Task paused or rule changed",0,null);
                    if(!DownloadRules.matches(message,rule(source))) return store.finish(job.getString("id"),"SKIPPED","No longer matches rule",0,null);
                    var handler=TdApiHelp.getFileHandler(message).orElseThrow();
                    return DataVerticle.fileRepository.getByUniqueId(handler.getFileUniqueId()).compose(existing -> {
                        if(existing!=null && (existing.isDownloadStatus(FileRecord.DownloadStatus.downloading)
                            || existing.isDownloadStatus(FileRecord.DownloadStatus.paused))) return Future.succeededFuture(existing);
                        if(existing!=null && existing.isDownloadStatus(FileRecord.DownloadStatus.completed)
                            && existing.localPath()!=null && new java.io.File(existing.localPath()).isFile()
                            && (existing.size()<=0 || new java.io.File(existing.localPath()).length()==existing.size()))
                            return Future.succeededFuture(existing);
                        if(existing!=null && existing.isTransferStatus(FileRecord.TransferStatus.completed))
                            return Future.succeededFuture(existing);
                        if(!storageAvailable(source,required)) return Future.failedFuture(new IllegalStateException("STORAGE_LOW: 存储空间不足，已为排队文件和安全水位预留空间"));
                        Future<Void> reset=existing!=null && existing.isDownloadStatus(FileRecord.DownloadStatus.completed)
                            ?DataVerticle.fileRepository.updateDownloadStatus(handler.getFileId(),existing.uniqueId(),existing.localPath(),FileRecord.DownloadStatus.idle,null).mapEmpty()
                            :Future.succeededFuture();
                        return reset.compose(_ -> telegram.startDownload(message.chatId,message.id,handler.getFileId()).timeout(60,TimeUnit.SECONDS));
                    }).compose(file -> store.finish(job.getString("id"),
                            file.isDownloadStatus(FileRecord.DownloadStatus.completed) || file.isTransferStatus(FileRecord.TransferStatus.completed)
                            ?"COMPLETED":file.isDownloadStatus(FileRecord.DownloadStatus.paused)?"PAUSED":"RUNNING",null,0,file.localPath()));
                })).recover(error -> fail(job,error,true));
        });
    }
    private Future<Void> fail(JsonObject job,Throwable error,boolean attemptNotYetCounted) {
        int attempt=(int)n(job,"attempts")+(attemptNotYetCounted?1:0);
        if (attempt < 1) attempt = 1;
        String errMsg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        boolean storage = errMsg.startsWith("STORAGE_LOW:");
        boolean terminal=!storage && (permanent(error) || attempt>=8);
        long retryAt=System.currentTimeMillis()+retryDelay(error,attempt);
        if(isFloodWait(errMsg)) accountCooldowns.put(n(job,"account_id"),retryAt);
        Future<Void> wait=storage || isFloodWait(errMsg)
            ?store.error(job.getString("source_id"),n(job,"rule_version"),errMsg,retryAt):Future.succeededFuture();
        return wait.compose(_ -> store.finish(job.getString("id"),terminal?"FAILED":"RETRY",errMsg,retryAt,null));
    }
    private Future<Void> scanComments() {
        return store.threads().compose(threads -> {
            if(threads.isEmpty()) return Future.succeededFuture();
            JsonObject thread=threads.getFirst();
            return store.source(thread.getString("source_id")).compose(source -> {
                if(!active(source) || !rule(source).downloadCommentFiles) return Future.succeededFuture();
                TelegramVerticle telegram=TelegramVerticles.get(n(source,"account_id")).orElse(null);
                if(telegram==null || !telegram.authorized || !diskAvailable()
                    || accountCooldowns.getOrDefault(n(source,"account_id"),0L)>System.currentTimeMillis())
                    return store.query("UPDATE channel_download_thread SET last_check=? WHERE id=? AND rule_version=?",
                        System.currentTimeMillis()+30_000,thread.getString("id"),n(thread,"rule_version")).mapEmpty();
                TdApi.GetMessageThreadHistory request=new TdApi.GetMessageThreadHistory();
                request.chatId=n(thread,"parent_chat");request.messageId=n(thread,"parent_message");
                request.fromMessageId=n(thread,"cursor_id");request.limit=100;
                return telegram.client.execute(request).timeout(30,TimeUnit.SECONDS).compose(page -> {
                    List<TdApi.Message> messages=Arrays.asList(page.messages);
                    long oldest=messages.stream().mapToLong(m -> m.id).min().orElse(0);
                    long target=Math.max(n(thread,"target_id"),messages.stream().mapToLong(m -> m.id).max().orElse(n(thread,"watermark")));
                    boolean done=messages.isEmpty() || oldest<=n(thread,"watermark") || oldest==n(thread,"cursor_id");
                    return store.threadPage(source,thread,matched(source,messages.stream().filter(m -> m.id>n(thread,"watermark")).toList()),oldest,target,done);
                }).recover(error -> {
                    long retryAt=System.currentTimeMillis()+retryDelay(error,2);
                    Future<Void> wait=Future.succeededFuture();
                    if(isFloodWait(Objects.toString(error.getMessage(),""))) {
                        accountCooldowns.put(n(source,"account_id"),retryAt);
                        wait=store.error(source.getString("id"),n(source,"rule_version"),error.getMessage(),retryAt);
                    }
                    return wait.compose(_ -> store.query("UPDATE channel_download_thread SET last_check=?,done=? WHERE id=? AND rule_version=?",
                        retryAt,permanent(error) || error instanceof TelegramRunException e && e.getError().code==400?2:0,thread.getString("id"),n(thread,"rule_version")).mapEmpty());
                });
            });
        });
    }
    private Future<Void> reconcile(List<JsonObject> activeJobs) {
        long now = System.currentTimeMillis();
        Future<Void> batchReconcile = Future.succeededFuture();
        if (now - lastReconcileCompletedTime > 60_000L && !reconcilingCompleted) {
            reconcilingCompleted = true;
            lastReconcileCompletedTime = now;
            batchReconcile = store.reconcileCompletedJobs()
                .onComplete(_ -> reconcilingCompleted = false)
                .mapEmpty();
        }
        List<JsonObject> jobs = new ArrayList<>(activeJobs);
        boolean fetchPaused = now - lastPausedReconcile > 15_000L;
        if (fetchPaused) lastPausedReconcile = now;
        return batchReconcile
            .compose(_ -> fetchPaused ? store.paused().onSuccess(jobs::addAll).mapEmpty() : Future.<Void>succeededFuture())
            .compose(_ -> reconcileJobs(jobs));
    }
    private Future<Void> reconcileJobs(List<JsonObject> jobs) {
        if (jobs.isEmpty()) return Future.succeededFuture();
        Future<Void> work=Future.succeededFuture();
        for(JsonObject job:jobs) work=work.compose(_ -> DataVerticle.fileRepository.getByUniqueId(job.getString("unique_id"))
            .compose(file -> {
                if(file!=null && file.isDownloadStatus(FileRecord.DownloadStatus.completed))
                    return store.finish(job.getString("id"),"COMPLETED",null,0,file.localPath());
                if("STARTING".equals(job.getString("status"))) {
                    if(file!=null && file.isDownloadStatus(FileRecord.DownloadStatus.downloading))
                        return store.finish(job.getString("id"),"RUNNING",null,0,null);
                    if(System.currentTimeMillis()-n(job,"updated_at")>60_000)
                        return fail(job,new IllegalStateException("Start download timed out; queued for recovery"),false);
                }
                if(file!=null && file.isDownloadStatus(FileRecord.DownloadStatus.downloading)
                    && System.currentTimeMillis()-n(job,"updated_at")>180_000) return refreshStale(job,file);
                return DataVerticle.torrentRepository.listByTelegramFileUniqueIds(List.of(job.getString("unique_id")))
                    .compose(torrents -> {
                        var seed=torrents.stream().filter(t -> Set.of("COMPLETED","SEEDING").contains(t.status())).findFirst();
                        if(seed.isPresent()) return store.finish(job.getString("id"),"COMPLETED",null,0,seed.get().savePath());
                        boolean paused=file!=null && file.isDownloadStatus(FileRecord.DownloadStatus.paused)
                            || torrents.stream().anyMatch(t -> "PAUSED".equals(t.status()));
                        if(paused) return "PAUSED".equals(job.getString("status"))?Future.succeededFuture()
                            :store.finish(job.getString("id"),"PAUSED","File paused by user",0,null);
                        if("PAUSED".equals(job.getString("status"))) {
                            if(file!=null && file.isDownloadStatus(FileRecord.DownloadStatus.downloading)
                                || torrents.stream().anyMatch(t -> "DOWNLOADING".equals(t.status())))
                                return store.finish(job.getString("id"),"RUNNING",null,0,null);
                            return Future.succeededFuture();
                        }
                        if(torrents.stream().anyMatch(t -> "STOPPED".equals(t.status())))
                            return store.finish(job.getString("id"),"SKIPPED","Download cancelled by user",0,null);
                        boolean activeSeed=torrents.stream().anyMatch(t -> Set.of("PENDING","QUEUED","DOWNLOADING","CHECKING","PAUSED").contains(t.status()));
                        if(!activeSeed && System.currentTimeMillis()-n(job,"updated_at")>180_000
                            && (file==null || !file.isDownloadStatus(FileRecord.DownloadStatus.downloading)))
                            return fail(job,new IllegalStateException("Interrupted download; queued for recovery"),false);
                        return Future.succeededFuture();
                    });
            }));
        return work;
    }
    private Future<Void> refreshStale(JsonObject job,FileRecord record) {
        TelegramVerticle telegram=TelegramVerticles.get(n(job,"account_id")).orElse(null);
        if(telegram==null || !telegram.authorized) return Future.succeededFuture();
        return telegram.client.execute(new TdApi.GetMessage(n(job,"chat_id"),n(job,"message_id")))
            .timeout(15,TimeUnit.SECONDS).compose(message -> {
                var handler=TdApiHelp.getFileHandler(message).orElseThrow();
                if(!record.uniqueId().equals(handler.getFileUniqueId())) return store.finish(job.getString("id"),"SKIPPED","Source media was replaced",0,null);
                return telegram.client.execute(new TdApi.GetFile(handler.getFileId())).timeout(15,TimeUnit.SECONDS)
                    .compose(file -> DataVerticle.fileRepository.updateFileId(file.id,record.uniqueId()).compose(_ -> {
                        if(file.local!=null && file.local.isDownloadingCompleted)
                            return DataVerticle.fileRepository.updateDownloadStatus(file.id,record.uniqueId(),file.local.path,
                                FileRecord.DownloadStatus.completed,System.currentTimeMillis())
                                .compose(_ -> store.finish(job.getString("id"),"COMPLETED",null,0,file.local.path));
                        if(file.local!=null && file.local.isDownloadingActive)
                            return store.finish(job.getString("id"),"RUNNING",null,0,null);
                        return DataVerticle.fileRepository.releaseStaleDownloadRetry(record.uniqueId(),record.startDate())
                            .compose(_ -> store.finish(job.getString("id"),"RETRY","Refreshed file identity after restart",0,null));
                    }));
            }).recover(error -> fail(job,error,false));
    }
    static boolean permanent(Throwable error) {
        if(!(error instanceof TelegramRunException e)) return false;
        return Set.of(401,403,404).contains(e.getError().code) || e.getError().code==400
            && (e.getError().message.contains("MESSAGE_ID_INVALID") || e.getError().message.contains("Can't access"));
    }
    static int occupiedSlots(long account,List<FileRecord> nativeDownloads,List<JsonObject> jobs) {
        Set<String> files=new HashSet<>();
        nativeDownloads.forEach(file -> files.add(file.uniqueId()));
        jobs.stream().filter(job -> n(job,"account_id")==account && !"PAUSED".equals(job.getString("status")))
            .forEach(job -> files.add(job.getString("unique_id")));
        return files.size();
    }
    static long retryDelay(Throwable error,int attempt) {
        String message=Objects.toString(error.getMessage(),"");
        java.util.regex.Matcher wait=java.util.regex.Pattern.compile("(?i)(?:FLOOD_WAIT[_ ]?|retry after |Too Many Requests: retry after )(\\d+)").matcher(message);
        if(wait.find()) try { return Math.max(1000,Math.min(86_400_000,Long.parseLong(wait.group(1))*1000)); }
            catch(NumberFormatException ignored) { }
        return Math.min(300_000,5_000L*(1L<<Math.min(6,Math.max(0,attempt-1))));
    }
    static boolean isFloodWait(String message) {
        String text=Objects.toString(message,"").toUpperCase(java.util.Locale.ROOT);
        return text.contains("FLOOD_WAIT") || text.contains("RETRY AFTER") || text.contains("TOO MANY REQUESTS");
    }
    static boolean allowedTime(SettingTimeLimitedDownload time) {
        if(time==null) return true;
        try {
            LocalTime start=LocalTime.parse(time.startTime),end=LocalTime.parse(time.endTime),now=LocalTime.now();
            return start.equals(end) || (start.isAfter(end)? !now.isBefore(start)||now.isBefore(end):!now.isBefore(start)&&now.isBefore(end));
        } catch(RuntimeException error) { return false; }
    }
    static boolean diskAvailable() {
        if(Config.APP_ROOT==null) return true;
        long bytes=new java.io.File(Config.APP_ROOT).getUsableSpace();
        return bytes>=1024L*1024*1024;
    }
    static boolean storageAvailable(JsonObject source,long required) {
        long reserve=1024L*1024*1024+Math.max(0,required);
        if(Config.APP_ROOT!=null && new java.io.File(Config.APP_ROOT).getUsableSpace()<reserve) return false;
        var auto=AutomationsHolder.INSTANCE.autoRecords().getItem(n(source,"account_id"),n(source,"chat_id"));
        if(auto!=null && auto.transfer!=null && auto.transfer.enabled && auto.transfer.rule.destination!=null
            && !auto.transfer.rule.destination.isBlank()) {
            java.io.File target=new java.io.File(auto.transfer.rule.destination);
            while(target!=null && !target.exists()) target=target.getParentFile();
            if(target!=null && target.exists() && target.getUsableSpace()<reserve) return false;
        }
        return true;
    }
    static boolean active(JsonObject source) { return source!=null && n(source,"enabled")==1 && n(source,"paused")==0; }
    static SettingAutoRecords.DownloadRule rule(JsonObject source) {
        return new JsonObject(source.getString("rule_json")).mapTo(SettingAutoRecords.DownloadRule.class);
    }
    private void log(String message,Throwable error) { cn.hutool.log.LogFactory.get().warn("{}: {}",message,error.getMessage()); }
    private void wakeAccount(TelegramVerticle telegram,long accountId,String message) {
        telegram.wake().onFailure(error -> {
            long now=System.currentTimeMillis();
            if(now-wakeFailureLogAt.getOrDefault(accountId,0L)>60_000L) {
                wakeFailureLogAt.put(accountId,now);
                log(message,error);
            }
        });
    }
}
