package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.CloudArchiveHistoryJob;
import telegram.files.repository.SettingAutoRecords;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Watches new Telegram messages and archives matching messages inside Telegram. */
public final class AutoCloudArchiveVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    private static final long RETRY_SCAN_INTERVAL = 5_000L;

    private static final long ALBUM_DEBOUNCE = 1_800L;

    private static final int SCAN_LIMIT = 200;

    private static final int MESSAGE_BATCH_SIZE = 20;

    private static final Duration HISTORY_STEP_TIMEOUT = Duration.ofMinutes(2);

    private static final int MAX_ATTEMPTS = 8;

    private static final long MAX_MESSAGES_PER_MINUTE = 20L;

    private static final long MAX_MESSAGES_PER_HOUR = 500L;

    private static final long MAX_MESSAGES_PER_DAY = 5_000L;

    private final SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();

    private final Map<AlbumKey, AlbumBuffer> albumBuffers = new HashMap<>();

    private final Map<Long, AccountQueue> queues = new HashMap<>();

    private final Set<String> queuedRecordIds = new HashSet<>();

    private final Set<Long> busyAccounts = new HashSet<>();

    private final Map<Long, Long> accountCooldownUntil = new HashMap<>();

    private boolean scanning;

    private boolean historyScanning;

    @Override
    public void start(Promise<Void> startPromise) {
        DataVerticle.cloudArchiveRepository.recoverSendingAsUnknown()
                .compose(_ -> initEventConsumer())
                .onSuccess(_ -> {
                    vertx.setPeriodic(0, RETRY_SCAN_INTERVAL, _ -> {
                        scanDue();
                        scanHistory();
                    });
                    log.info("Cloud archive verticle started! Auto chats: {}",
                            autoRecords.getArchiveEnabledItems().size());
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop() {
        albumBuffers.values().forEach(buffer -> vertx.cancelTimer(buffer.timerId));
        albumBuffers.clear();
        queues.clear();
        queuedRecordIds.clear();
        busyAccounts.clear();
        accountCooldownUntil.clear();
        log.info("Cloud archive verticle stopped");
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.MESSAGE_RECEIVED.address(), event ->
                onNewMessage((JsonObject) event.body()));
        return Future.succeededFuture();
    }

    private void onNewMessage(JsonObject payload) {
        long telegramId = payload.getLong("telegramId", 0L);
        long chatId = payload.getLong("chatId", 0L);
        long messageId = payload.getLong("messageId", 0L);
        SettingAutoRecords.Automation automation = autoRecords.getItem(telegramId, chatId);
        if (!isEnabled(automation) || messageId == 0) {
            return;
        }
        TelegramVerticles.get(telegramId).ifPresent(telegram ->
                telegram.client.execute(new TdApi.GetMessage(chatId, messageId))
                        .onSuccess(message -> collectMessage(automation, message))
                        .onFailure(failure -> log.warn(
                                "Failed to read cloud archive source {}:{}: {}",
                                chatId, messageId, failure.getMessage())));
    }

    private void collectMessage(SettingAutoRecords.Automation automation, TdApi.Message message) {
        if (message == null || message.sendingState instanceof TdApi.MessageSendingStatePending
            || !matchesTopic(message, automation.archive.rule.sourceTopicId)) {
            return;
        }
        if (message.mediaAlbumId == 0) {
            enqueueMatchingMessages(automation, List.of(message));
            return;
        }

        AlbumKey key = new AlbumKey(automation.telegramId, automation.chatId, message.mediaAlbumId);
        AlbumBuffer existing = albumBuffers.get(key);
        if (existing != null) {
            existing.messageIds.add(message.id);
            return;
        }

        AlbumBuffer buffer = new AlbumBuffer();
        buffer.messageIds.add(message.id);
        buffer.timerId = vertx.setTimer(ALBUM_DEBOUNCE, _ -> flushAlbum(key));
        albumBuffers.put(key, buffer);
    }

    private void flushAlbum(AlbumKey key) {
        AlbumBuffer buffer = albumBuffers.remove(key);
        if (buffer == null) {
            return;
        }
        SettingAutoRecords.Automation automation = autoRecords.getItem(key.telegramId, key.chatId);
        if (!isEnabled(automation)) {
            return;
        }
        TelegramVerticles.get(key.telegramId).ifPresent(telegram -> {
            long[] observedIds = buffer.messageIds.stream().mapToLong(Long::longValue).sorted().toArray();
            telegram.client.execute(new TdApi.GetMessages(key.chatId, observedIds))
                    .compose(observed -> {
                        TdApi.Message[] observedMessages = observed == null || observed.messages == null
                                ? new TdApi.Message[0]
                                : observed.messages;
                        TdApi.Message seed = Arrays.stream(observedMessages)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null);
                        if (seed == null) {
                            return Future.succeededFuture(observedMessages);
                        }
                        return FileRecordRetriever.getAlbumMessages(key.telegramId, seed)
                                .map(searched -> mergeMessages(observedMessages, searched));
                    })
                    .onSuccess(messages -> {
                        Map<Long, TdApi.Message> unique = new LinkedHashMap<>();
                        Arrays.stream(messages == null ? new TdApi.Message[0] : messages)
                                .filter(Objects::nonNull)
                                .filter(message -> message.mediaAlbumId == key.albumId)
                                .sorted((left, right) -> Long.compare(left.id, right.id))
                                .forEach(message -> unique.put(message.id, message));
                        enqueueMatchingMessages(automation, new ArrayList<>(unique.values()));
                    })
                    .onFailure(failure -> log.warn("Failed to collect Telegram album {}: {}",
                            key.albumId, failure.getMessage()));
        });
    }

    private void enqueueMatchingMessages(SettingAutoRecords.Automation automation,
                                         List<TdApi.Message> messages) {
        if (CollUtil.isEmpty(messages) || !isEnabled(automation)) {
            return;
        }
        SettingAutoRecords.ArchiveRule rule = automation.archive.rule;
        if (messages.stream().noneMatch(message -> matches(message, rule))) {
            return;
        }

        DataVerticle.cloudArchiveHistoryRepository
                .findActive(automation.telegramId, automation.chatId)
                .compose(historyJob -> Future.all(messages.stream()
                        .map(message -> {
                            long sourceTopicId = messageTopicId(message);
                            return resolveTargetTopic(automation.telegramId, automation.chatId,
                                    sourceTopicId, rule).compose(targetTopicId -> {
                                if (shouldStage(historyJob)) {
                                    return DataVerticle.cloudArchiveRepository.stage(
                                            automation.telegramId, automation.chatId, sourceTopicId,
                                            message.id, message.mediaAlbumId, rule.targetChatId,
                                            targetTopicId, TdApiHelp.getFileUniqueId(message),
                                            effectiveMode(rule).name(), effectiveTopicMode(rule).name(),
                                            historyJob.id());
                                }
                                return DataVerticle.cloudArchiveRepository.enqueue(
                                        automation.telegramId, automation.chatId, sourceTopicId,
                                        message.id, message.mediaAlbumId, rule.targetChatId,
                                        targetTopicId, TdApiHelp.getFileUniqueId(message),
                                        effectiveMode(rule).name(), effectiveTopicMode(rule).name());
                            });
                        })
                        .toList()))
                .onSuccess(_ -> scanDue())
                .onFailure(failure -> log.error(failure, "Failed to enqueue cloud archive messages"));
    }

    private Future<Long> resolveTargetTopic(long telegramId,
                                            long sourceChatId,
                                            long sourceTopicId,
                                            SettingAutoRecords.ArchiveRule rule) {
        if (effectiveTopicMode(rule) != SettingAutoRecords.ArchiveTopicMode.PRESERVE) {
            return Future.succeededFuture(rule.targetTopicId);
        }
        TelegramVerticle telegram = TelegramVerticles.get(telegramId).orElse(null);
        if (telegram == null || !telegram.isAvailable()) {
            return Future.failedFuture("The Telegram account is unavailable");
        }
        if (sourceTopicId == 0) {
            return Future.failedFuture("A source forum topic is required to preserve topic structure");
        }
        return CloudArchiveTopicService.resolve(
                telegram, sourceChatId, sourceTopicId, rule.targetChatId);
    }

    private boolean matches(TdApi.Message message, SettingAutoRecords.ArchiveRule rule) {
        boolean hasFile = TdApiHelp.getFileHandler(message).isPresent();
        SettingAutoRecords.ArchiveScope scope = rule.scope == null
                ? SettingAutoRecords.ArchiveScope.ALL_MESSAGES
                : rule.scope;
        if (scope == SettingAutoRecords.ArchiveScope.MEDIA_ONLY && !hasFile) {
            return false;
        }
        if (scope == SettingAutoRecords.ArchiveScope.MEDIA_ONLY
            && CollUtil.isNotEmpty(rule.fileTypes)) {
            String type = TdApiHelp.getFileHandler(message)
                    .map(handler -> handler.convertFileRecord(0).type())
                    .orElse("");
            if (!rule.fileTypes.contains(type)) {
                return false;
            }
        }
        if (StrUtil.isNotBlank(rule.query)) {
            String text = messageText(message).toLowerCase(Locale.ROOT);
            if (!text.contains(rule.query.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return StrUtil.isBlank(rule.filterExpr) || MessageFilter.filter(rule.filterExpr).test(message);
    }

    private static boolean matchesTopic(TdApi.Message message, long sourceTopicId) {
        if (sourceTopicId == 0) {
            return true;
        }
        return message.topicId instanceof TdApi.MessageTopicForum topic
               && topic.forumTopicId == sourceTopicId;
    }

    private static String messageText(TdApi.Message message) {
        return switch (message.content) {
            case TdApi.MessageText value -> value.text.text;
            case TdApi.MessagePhoto value -> value.caption.text;
            case TdApi.MessageVideo value -> value.caption.text;
            case TdApi.MessageAudio value -> value.caption.text;
            case TdApi.MessageDocument value -> value.caption.text;
            case TdApi.MessageAnimation value -> value.caption.text;
            default -> "";
        };
    }

    private void scanDue() {
        if (scanning) {
            return;
        }
        scanning = true;
        DataVerticle.cloudArchiveRepository.listDue(System.currentTimeMillis(), SCAN_LIMIT)
                .onSuccess(records -> {
                    Map<String, List<CloudArchiveRecord>> batches = new LinkedHashMap<>();
                    Map<String, Integer> batchIndexes = new HashMap<>();
                    for (CloudArchiveRecord record : records) {
                        if (queuedRecordIds.contains(record.id())) {
                            continue;
                        }
                        SettingAutoRecords.Automation automation = autoRecords.getItem(
                                record.telegramId(), record.sourceChatId());
                        SettingAutoRecords.ArchiveRule configuredRule = automation == null
                                || automation.archive == null ? null : automation.archive.rule;
                        boolean unresolvedPreservedTopic = record.sourceTopicId() == 0
                                && preservesTopics(record, configuredRule);
                        String route = "%d:%d:%d:%d:%d:%s:%s:%s".formatted(
                                record.telegramId(), record.sourceChatId(), record.sourceTopicId(),
                                record.targetChatId(), record.targetTopicId(), record.mode(),
                                record.topicMode(), record.historyJobId() == null ? "live" : record.historyJobId());
                        String key;
                        if (record.sourceAlbumId() != 0) {
                            key = "album:" + route + ":" + record.sourceAlbumId();
                        } else if (unresolvedPreservedTopic) {
                            // Older queue rows don't contain a source topic. Resolve them one by one
                            // so messages from different topics can never share one Telegram request.
                            key = "unresolved-topic:" + record.id();
                        } else {
                            int batchIndex = batchIndexes.getOrDefault(route, 0);
                            key = "messages:" + route + ":" + batchIndex;
                            if (batches.getOrDefault(key, List.of()).size() >= MESSAGE_BATCH_SIZE) {
                                batchIndex++;
                                batchIndexes.put(route, batchIndex);
                                key = "messages:" + route + ":" + batchIndex;
                            }
                        }
                        batches.computeIfAbsent(key, _ -> new ArrayList<>()).add(record);
                    }
                    batches.values().forEach(this::queue);
                })
                .onFailure(failure -> log.error(failure, "Failed to scan pending cloud archive records"))
                .onComplete(_ -> scanning = false);
    }

    private void scanHistory() {
        if (historyScanning) {
            return;
        }
        historyScanning = true;
        DataVerticle.cloudArchiveHistoryRepository.listRunnable(20)
                .compose(jobs -> jobs.isEmpty()
                        ? Future.succeededFuture()
                        : processHistoryPage(jobs.getFirst()))
                .onFailure(failure -> log.error(failure, "Failed to scan cloud archive history"))
                .onComplete(_ -> historyScanning = false);
    }

    private Future<Void> processHistoryPage(CloudArchiveHistoryJob job) {
        if ("DRAINING".equals(job.status())) {
            return DataVerticle.cloudArchiveRepository.releaseHistory(job.id())
                    .compose(_ -> DataVerticle.cloudArchiveRepository.countOutstandingHistory(job.id()))
                    .compose(outstanding -> {
                        scanDue();
                        return outstanding == 0
                                ? DataVerticle.cloudArchiveHistoryRepository.completeDraining(job.id())
                                : Future.succeededFuture();
                    });
        }
        return DataVerticle.cloudArchiveHistoryRepository.start(job.id()).compose(started -> {
            if (!started) {
                return Future.succeededFuture();
            }
            TelegramVerticle telegram = TelegramVerticles.get(job.telegramId()).orElse(null);
            if (telegram == null || !telegram.isAvailable()) {
                return DataVerticle.cloudArchiveHistoryRepository.fail(
                        job.id(), "The Telegram account is unavailable");
            }
            SettingAutoRecords.ArchiveRule rule;
            try {
                rule = new JsonObject(job.ruleJson()).mapTo(SettingAutoRecords.ArchiveRule.class);
            } catch (RuntimeException failure) {
                return DataVerticle.cloudArchiveHistoryRepository.fail(
                        job.id(), "The saved archive rule is invalid");
            }
            if ("DISCOVERING".equals(job.stage())) {
                return initializeHistoryTopics(telegram, job, rule);
            }
            boolean allHistory = "ALL".equals(job.scanMode());
            int remaining = allHistory
                    ? 50 : Math.max(0, job.maxMessages() - job.scannedCount());
            if (!allHistory && remaining == 0) {
                return finishHistoryScan(job, "LIMIT_REACHED");
            }
            int pageSize = Math.min(50, remaining);
            return historyMessages(telegram, job, pageSize).compose(rawMessages -> {
                TdApi.Message[] messages = Arrays.stream(rawMessages)
                        .filter(Objects::nonNull)
                        .filter(message -> job.fromMessageId() == 0 || message.id != job.fromMessageId())
                        .limit(pageSize)
                        .toArray(TdApi.Message[]::new);
                List<TdApi.Message> candidates = Arrays.stream(messages)
                        .filter(Objects::nonNull)
                        .filter(message -> matchesTopic(message, job.currentTopicId()))
                        .filter(message -> matches(message, rule))
                        .toList();
                return resolveHistoryTargetTopic(telegram, job, rule).compose(targetTopicId -> {
                    List<Future<Boolean>> inserts = candidates.stream()
                            .map(message -> DataVerticle.cloudArchiveRepository.stage(
                                    job.telegramId(), job.sourceChatId(), messageTopicId(message),
                                    message.id, message.mediaAlbumId, job.targetChatId(),
                                    targetTopicId, TdApiHelp.getFileUniqueId(message),
                                    effectiveMode(rule).name(), effectiveTopicMode(rule).name(), job.id()))
                            .toList();
                    return Future.all(inserts);
                }).compose(results -> {
                    int queued = 0;
                    for (int index = 0; index < candidates.size(); index++) {
                        if (Boolean.TRUE.equals(results.resultAt(index))) {
                            queued++;
                        }
                    }
                    long cursor = Arrays.stream(messages)
                            .filter(Objects::nonNull)
                            .mapToLong(message -> message.id)
                            .min()
                            .orElse(job.fromMessageId());
                    return DataVerticle.cloudArchiveHistoryRepository.advance(
                            job.id(), cursor, messages.length, candidates.size(), queued)
                            .compose(_ -> {
                                boolean limitReached = !allHistory
                                                       && job.scannedCount() + messages.length >= job.maxMessages();
                                if (limitReached) {
                                    return finishHistoryScan(job, "LIMIT_REACHED");
                                }
                                return messages.length == 0
                                        ? advanceHistoryTopic(job)
                                        : Future.succeededFuture();
                            });
                });
            }).timeout(HISTORY_STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .recover(failure -> DataVerticle.cloudArchiveHistoryRepository.fail(
                            job.id(), safeMessage(failure)));
        });
    }

    private Future<TdApi.Message[]> historyMessages(TelegramVerticle telegram,
                                                     CloudArchiveHistoryJob job,
                                                     int limit) {
        int requestLimit = Math.min(100, limit + (job.fromMessageId() == 0 ? 0 : 1));
        if (job.currentTopicId() != 0) {
            return telegram.client.execute(new TdApi.GetForumTopicHistory(
                            job.sourceChatId(), Math.toIntExact(job.currentTopicId()),
                            job.fromMessageId(), 0, requestLimit))
                    .map(found -> found == null || found.messages == null
                            ? new TdApi.Message[0] : found.messages);
        }
        return telegram.client.execute(new TdApi.GetChatHistory(
                        job.sourceChatId(), job.fromMessageId(), 0, requestLimit, false))
                .map(found -> found == null || found.messages == null
                        ? new TdApi.Message[0] : found.messages);
    }

    Future<Void> initializeHistoryTopics(TelegramVerticle telegram,
                                         CloudArchiveHistoryJob job,
                                         SettingAutoRecords.ArchiveRule rule) {
        if (effectiveTopicMode(rule) != SettingAutoRecords.ArchiveTopicMode.PRESERVE) {
            long topicId = job.sourceTopicId();
            return DataVerticle.cloudArchiveHistoryRepository.initializeTopics(
                    job.id(), new JsonArray(List.of(topicId)).encode(), 1, topicId);
        }
        if (!telegram.isForum(job.sourceChatId()) || !telegram.isForum(job.targetChatId())) {
            return DataVerticle.cloudArchiveHistoryRepository.fail(job.id(),
                    "Preserving topics requires both source and destination to be forum groups");
        }
        return TelegramTopics.listAll(telegram.client, job.sourceChatId(), "")
                .compose(topics -> {
                    List<TdApi.ForumTopic> selected = topics.stream()
                            .filter(topic -> job.sourceTopicId() == 0
                                             || topic.info.forumTopicId == job.sourceTopicId())
                            .toList();
                    if (selected.isEmpty()) {
                        return Future.failedFuture("No source forum topics were found");
                    }
                    // Don't create every destination topic up front. Large forums can otherwise
                    // remain at 0 scanned while Telegram serializes or rate-limits topic creation.
                    // Each mapping is resolved lazily when its source topic starts scanning.
                    List<Long> ids = selected.stream()
                            .map(topic -> (long) topic.info.forumTopicId)
                            .toList();
                    return DataVerticle.cloudArchiveHistoryRepository.initializeTopics(
                            job.id(), new JsonArray(ids).encode(), ids.size(), ids.getFirst());
                })
                .timeout(HISTORY_STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .recover(failure -> DataVerticle.cloudArchiveHistoryRepository.fail(
                        job.id(), safeMessage(failure)));
    }

    private Future<Long> resolveHistoryTargetTopic(TelegramVerticle telegram,
                                                   CloudArchiveHistoryJob job,
                                                   SettingAutoRecords.ArchiveRule rule) {
        return effectiveTopicMode(rule) == SettingAutoRecords.ArchiveTopicMode.PRESERVE
                ? CloudArchiveTopicService.resolve(telegram, job.sourceChatId(),
                        job.currentTopicId(), job.targetChatId())
                : Future.succeededFuture(job.targetTopicId());
    }

    private Future<Void> advanceHistoryTopic(CloudArchiveHistoryJob job) {
        List<Long> topicIds = historyTopicIds(job);
        int nextIndex = job.topicIndex() + 1;
        if (nextIndex >= topicIds.size()) {
            return finishHistoryScan(job, "HISTORY_END");
        }
        return DataVerticle.cloudArchiveHistoryRepository.completeTopic(
                job.id(), nextIndex, topicIds.get(nextIndex), false, null);
    }

    private Future<Void> finishHistoryScan(CloudArchiveHistoryJob job, String reason) {
        return DataVerticle.cloudArchiveHistoryRepository.completeTopic(
                job.id(), job.topicCount(), 0, true, reason);
    }

    private static List<Long> historyTopicIds(CloudArchiveHistoryJob job) {
        if (StrUtil.isBlank(job.topicIdsJson())) {
            return List.of(job.sourceTopicId());
        }
        return new JsonArray(job.topicIdsJson()).stream()
                .map(value -> ((Number) value).longValue())
                .toList();
    }

    private static boolean shouldStage(CloudArchiveHistoryJob job) {
        return job != null && !"DRAINING".equals(job.status());
    }

    private static long messageTopicId(TdApi.Message message) {
        return message != null && message.topicId instanceof TdApi.MessageTopicForum topic
                ? topic.forumTopicId : 0L;
    }

    private void queue(List<CloudArchiveRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        records.forEach(record -> queuedRecordIds.add(record.id()));
        long telegramId = records.getFirst().telegramId();
        queues.computeIfAbsent(telegramId, _ -> new AccountQueue())
                .add(new ArchiveWork(List.copyOf(records)));
        drain(telegramId);
    }

    private void drain(long telegramId) {
        if (busyAccounts.contains(telegramId)) {
            return;
        }
        AccountQueue queue = queues.get(telegramId);
        ArchiveWork work = queue == null ? null : queue.poll();
        if (work == null) {
            return;
        }
        busyAccounts.add(telegramId);
        process(work)
                .onFailure(failure -> log.error(failure, "Cloud archive work failed"))
                .onComplete(_ -> {
                    busyAccounts.remove(telegramId);
                    work.records.forEach(record -> queuedRecordIds.remove(record.id()));
                    vertx.setTimer(3_000L, _ -> drain(telegramId));
                });
    }

    private Future<Void> process(ArchiveWork work) {
        CloudArchiveRecord first = work.records.getFirst();
        long now = System.currentTimeMillis();
        return DataVerticle.cloudArchiveRepository.cooldownUntil(first.telegramId(), now)
                .compose(persistedCooldown -> {
                    long cooldownUntil = Math.max(persistedCooldown,
                            accountCooldownUntil.getOrDefault(first.telegramId(), 0L));
                    if (cooldownUntil > now) {
                        return deferAll(work.records, cooldownUntil, "TELEGRAM_WAIT",
                                "Telegram requested a temporary account cooldown");
                    }
                    accountCooldownUntil.remove(first.telegramId());
                    return processAfterCooldown(work, first, now);
                });
    }

    private Future<Void> processAfterCooldown(ArchiveWork work,
                                              CloudArchiveRecord first,
                                              long now) {
        SettingAutoRecords.Automation automation = autoRecords.getItem(first.telegramId(), first.sourceChatId());
        SettingAutoRecords.ArchiveRule configuredRule = automation == null || automation.archive == null
                ? null : automation.archive.rule;
        boolean topicMismatch = configuredRule != null
                                && configuredRule.sourceTopicId != 0
                                && first.sourceTopicId() != 0
                                && configuredRule.sourceTopicId != first.sourceTopicId();
        boolean targetTopicMismatch = configuredRule != null
                                      && !preservesTopics(first, configuredRule)
                                      && effectiveTopicMode(configuredRule) != SettingAutoRecords.ArchiveTopicMode.PRESERVE
                                      && configuredRule.targetTopicId != first.targetTopicId();
        if (!isEnabled(automation) || configuredRule.targetChatId != first.targetChatId()
            || topicMismatch || targetTopicMismatch) {
            return failAll(work.records, false, "RULE_DISABLED", "The cloud archive rule is disabled or changed");
        }
        return Future.all(
                        DataVerticle.cloudArchiveRepository.countCompletedSince(
                                first.telegramId(), now - Duration.ofMinutes(1).toMillis()),
                        DataVerticle.cloudArchiveRepository.countCompletedSince(
                                first.telegramId(), now - Duration.ofHours(1).toMillis()),
                        DataVerticle.cloudArchiveRepository.countCompletedSince(
                                first.telegramId(), now - Duration.ofDays(1).toMillis()))
                .compose(counts -> {
                    long minute = counts.resultAt(0);
                    long hourly = counts.resultAt(1);
                    long daily = counts.resultAt(2);
                    int requested = work.records.size();
                    if (minute + requested > MAX_MESSAGES_PER_MINUTE
                        || hourly + requested > MAX_MESSAGES_PER_HOUR
                        || daily + requested > MAX_MESSAGES_PER_DAY) {
                        long delay = daily + requested > MAX_MESSAGES_PER_DAY
                                ? Duration.ofHours(1).toMillis()
                                : hourly + requested > MAX_MESSAGES_PER_HOUR
                                  ? Duration.ofMinutes(5).toMillis()
                                  : Duration.ofMinutes(1).toMillis();
                        return deferAll(work.records, now + delay,
                                daily + requested > MAX_MESSAGES_PER_DAY
                                        ? "DAILY_SAFETY_LIMIT"
                                        : hourly + requested > MAX_MESSAGES_PER_HOUR
                                          ? "HOURLY_SAFETY_LIMIT"
                                          : "MINUTE_SAFETY_LIMIT",
                                "The account safety limit paused cloud archiving");
                    }
                    return processWithinBudget(work, automation, first);
                });
    }

    private Future<Void> processWithinBudget(ArchiveWork work,
                                             SettingAutoRecords.Automation automation,
                                             CloudArchiveRecord first) {
        List<Future<Boolean>> claims = work.records.stream()
                .map(record -> DataVerticle.cloudArchiveRepository.claim(record.id()))
                .toList();
        return Future.all(claims).compose(results -> {
            List<CloudArchiveRecord> claimed = new ArrayList<>();
            for (int index = 0; index < work.records.size(); index++) {
                if (Boolean.TRUE.equals(results.resultAt(index))) {
                    claimed.add(work.records.get(index));
                }
            }
            if (claimed.isEmpty()) {
                return Future.succeededFuture();
            }

            TelegramVerticle telegram = TelegramVerticles.get(first.telegramId()).orElse(null);
            if (telegram == null || !telegram.isAvailable()) {
                return failAll(claimed, true, "ACCOUNT_UNAVAILABLE", "The Telegram account is unavailable");
            }

            return prepareTopics(telegram, claimed, automation.archive.rule)
                    .compose(prepared -> {
                        CloudArchiveRecord preparedFirst = prepared.getFirst();
                        SettingAutoRecords.ArchiveRule rule = copyRule(
                                automation.archive.rule, preparedFirst);
                        List<Long> ids = prepared.stream()
                                .map(CloudArchiveRecord::sourceMessageId).sorted().toList();
                        return CloudArchiveService.archive(
                                        telegram, preparedFirst.sourceChatId(), ids, rule)
                                .compose(targets -> completeAll(prepared, targets));
                    })
                    .recover(failure -> handleFailure(claimed, failure));
        });
    }

    private Future<List<CloudArchiveRecord>> prepareTopics(
            TelegramVerticle telegram,
            List<CloudArchiveRecord> records,
            SettingAutoRecords.ArchiveRule configuredRule) {
        if (records.stream().noneMatch(record -> preservesTopics(record, configuredRule))) {
            return Future.succeededFuture(records);
        }
        CloudArchiveRecord first = records.getFirst();
        if (!telegram.isForum(first.sourceChatId())) {
            return Future.failedFuture(new TopicResolutionFailure(
                    "SOURCE_NOT_FORUM", "Preserving topics requires a forum source group", false));
        }
        if (!telegram.isForum(first.targetChatId())) {
            return Future.failedFuture(new TopicResolutionFailure(
                    "TARGET_NOT_FORUM", "Preserving topics requires a forum destination group", false));
        }
        return Future.all(records.stream()
                        .map(record -> prepareTopic(telegram, record))
                        .toList())
                .map(results -> {
                    List<CloudArchiveRecord> prepared = new ArrayList<>(results.size());
                    for (int index = 0; index < results.size(); index++) {
                        prepared.add(results.resultAt(index));
                    }
                    return prepared;
                })
                .compose(prepared -> configuredRule != null
                        && configuredRule.sourceTopicId != 0
                        && prepared.stream().anyMatch(record ->
                                record.sourceTopicId() != configuredRule.sourceTopicId)
                        ? Future.failedFuture(new TopicResolutionFailure(
                                "RULE_CHANGED",
                                "The queued message no longer matches the selected source topic",
                                false))
                        : Future.succeededFuture(prepared));
    }

    private Future<CloudArchiveRecord> prepareTopic(TelegramVerticle telegram,
                                                     CloudArchiveRecord record) {
        Future<Long> sourceTopic = record.sourceTopicId() != 0
                ? Future.succeededFuture(record.sourceTopicId())
                : telegram.client.execute(new TdApi.GetMessage(
                                record.sourceChatId(), record.sourceMessageId()))
                        .map(AutoCloudArchiveVerticle::messageTopicId);
        return sourceTopic.compose(sourceTopicId -> {
                    if (sourceTopicId == 0) {
                        return Future.failedFuture(new TopicResolutionFailure(
                                "SOURCE_TOPIC_REQUIRED",
                                "The source message doesn't belong to a forum topic", false));
                    }
                    return CloudArchiveTopicService.resolve(
                                    telegram, record.sourceChatId(), sourceTopicId,
                                    record.targetChatId())
                            .compose(targetTopicId -> {
                                if (targetTopicId == 0) {
                                    return Future.failedFuture(new TopicResolutionFailure(
                                            "TARGET_TOPIC_REQUIRED",
                                            "Telegram didn't return a destination topic", false));
                                }
                                return DataVerticle.cloudArchiveRepository.updateTopics(
                                                record.id(), sourceTopicId, targetTopicId,
                                                SettingAutoRecords.ArchiveTopicMode.PRESERVE.name())
                                        .map(_ -> withTopics(record, sourceTopicId, targetTopicId));
                            });
                })
                .recover(failure -> failure instanceof TopicResolutionFailure
                        ? Future.failedFuture(failure)
                        : Future.failedFuture(new TopicResolutionFailure(
                                "TOPIC_RESOLVE_FAILED", safeMessage(failure),
                                CloudArchiveService.isRetryable(failure), failure)));
    }

    private Future<Void> deferAll(List<CloudArchiveRecord> records,
                                  long nextAttemptAt,
                                  String code,
                                  String message) {
        return Future.all(records.stream()
                        .map(record -> DataVerticle.cloudArchiveRepository.defer(
                                record.id(), nextAttemptAt, code, message))
                        .toList())
                .mapEmpty();
    }

    private Future<Void> completeAll(List<CloudArchiveRecord> records, Map<Long, Long> targets) {
        List<Future<Void>> updates = records.stream().map(record -> {
            long targetMessageId = targets.getOrDefault(record.sourceMessageId(), 0L);
            if (targetMessageId == 0) {
                return DataVerticle.cloudArchiveRepository.fail(
                        record.id(), false, 0L, "TELEGRAM_REJECTED_MESSAGE",
                        "Telegram didn't return a destination message"
                );
            }
            return DataVerticle.cloudArchiveRepository.complete(record.id(), targetMessageId);
        }).toList();
        return Future.all(updates).mapEmpty();
    }

    private Future<Void> handleFailure(List<CloudArchiveRecord> records, Throwable failure) {
        if (failure instanceof TopicResolutionFailure topicFailure) {
            return failAll(records, topicFailure.retryable(),
                    topicFailure.code(), topicFailure.getMessage());
        }
        if (failure instanceof CloudArchiveService.Rejected rejected) {
            if (rejected.code().startsWith("TOPIC_")) {
                return failAll(records, false, rejected.code(), rejected.getMessage());
            }
            List<Future<Void>> updates = records.stream()
                    .map(record -> DataVerticle.cloudArchiveRepository.skip(
                            record.id(), rejected.code(), rejected.getMessage()))
                    .toList();
            return Future.all(updates).mapEmpty();
        }
        boolean staleTopic = CloudArchiveService.isTopicFailure(failure);
        if (staleTopic) {
            records.forEach(record -> CloudArchiveTopicService.invalidate(
                    record.telegramId(), record.sourceChatId(), record.sourceTopicId(),
                    record.targetChatId()));
        }
        boolean retryable = staleTopic || CloudArchiveService.isRetryable(failure);
        long telegramWaitMillis = CloudArchiveService.retryAfterMillis(failure);
        if (telegramWaitMillis > 0 && !records.isEmpty()) {
            accountCooldownUntil.merge(records.getFirst().telegramId(),
                    System.currentTimeMillis() + telegramWaitMillis, Math::max);
        }
        return failAll(records, retryable,
                telegramWaitMillis > 0 ? "TELEGRAM_WAIT"
                        : staleTopic ? "TOPIC_MAPPING_STALE" : CloudArchiveService.errorCode(failure),
                safeMessage(failure),
                telegramWaitMillis);
    }

    private Future<Void> failAll(List<CloudArchiveRecord> records,
                                 boolean retryable,
                                 String code,
                                 String message) {
        return failAll(records, retryable, code, message, 0L);
    }

    private Future<Void> failAll(List<CloudArchiveRecord> records,
                                 boolean retryable,
                                 String code,
                                 String message,
                                 long telegramWaitMillis) {
        List<Future<Void>> updates = records.stream()
                .map(record -> {
                    int attempt = record.attemptCount() + 1;
                    boolean shouldRetry = retryable && attempt < MAX_ATTEMPTS;
                    return DataVerticle.cloudArchiveRepository.fail(
                            record.id(),
                            shouldRetry,
                            shouldRetry ? System.currentTimeMillis()
                                    + Math.max(retryDelay(attempt), telegramWaitMillis) : 0L,
                            shouldRetry ? code : retryable ? "MAX_RETRIES_EXCEEDED" : code,
                            message
                    );
                })
                .toList();
        return Future.all(updates).mapEmpty();
    }

    private static TdApi.Message[] mergeMessages(TdApi.Message[] first, TdApi.Message[] second) {
        Map<Long, TdApi.Message> messages = new LinkedHashMap<>();
        Arrays.stream(first == null ? new TdApi.Message[0] : first)
                .filter(Objects::nonNull)
                .forEach(message -> messages.put(message.id, message));
        Arrays.stream(second == null ? new TdApi.Message[0] : second)
                .filter(Objects::nonNull)
                .forEach(message -> messages.put(message.id, message));
        return messages.values().toArray(TdApi.Message[]::new);
    }

    private static long retryDelay(int attempt) {
        long base = Duration.ofSeconds(30).toMillis();
        return Math.min(Duration.ofMinutes(30).toMillis(), base << Math.min(Math.max(attempt - 1, 0), 6));
    }

    private static SettingAutoRecords.ArchiveRule copyRule(SettingAutoRecords.ArchiveRule configured,
                                                           CloudArchiveRecord record) {
        SettingAutoRecords.ArchiveRule copy = new SettingAutoRecords.ArchiveRule();
        copy.targetChatId = record.targetChatId();
        copy.sourceTopicId = record.sourceTopicId();
        copy.targetTopicId = record.targetTopicId();
        copy.topicMode = recordTopicMode(record);
        copy.mode = SettingAutoRecords.ArchiveMode.valueOf(record.mode());
        copy.scope = configured.scope;
        copy.fileTypes = configured.fileTypes;
        copy.query = configured.query;
        copy.filterExpr = configured.filterExpr;
        copy.preserveCaption = configured.preserveCaption;
        copy.disableNotification = configured.disableNotification;
        return copy;
    }

    private static boolean preservesTopics(CloudArchiveRecord record,
                                           SettingAutoRecords.ArchiveRule configuredRule) {
        return recordTopicMode(record) == SettingAutoRecords.ArchiveTopicMode.PRESERVE
               || configuredRule != null
                  && effectiveTopicMode(configuredRule) == SettingAutoRecords.ArchiveTopicMode.PRESERVE;
    }

    private static SettingAutoRecords.ArchiveTopicMode recordTopicMode(CloudArchiveRecord record) {
        try {
            return SettingAutoRecords.ArchiveTopicMode.valueOf(
                    StrUtil.blankToDefault(record.topicMode(), "MERGE"));
        } catch (IllegalArgumentException ignored) {
            return SettingAutoRecords.ArchiveTopicMode.MERGE;
        }
    }

    private static CloudArchiveRecord withTopics(CloudArchiveRecord record,
                                                 long sourceTopicId,
                                                 long targetTopicId) {
        return new CloudArchiveRecord(
                record.id(), record.telegramId(), record.sourceChatId(), sourceTopicId,
                record.sourceMessageId(), record.sourceAlbumId(), record.targetChatId(),
                targetTopicId, record.targetMessageId(), record.fileUniqueId(), record.mode(),
                SettingAutoRecords.ArchiveTopicMode.PRESERVE.name(), record.historyJobId(),
                record.deliverySequence(), record.status(), record.attemptCount(),
                record.nextAttemptAt(), record.lastErrorCode(), record.lastErrorMessage(),
                record.createdAt(), record.updatedAt());
    }

    private static SettingAutoRecords.ArchiveMode effectiveMode(SettingAutoRecords.ArchiveRule rule) {
        return rule.mode == null ? SettingAutoRecords.ArchiveMode.COPY : rule.mode;
    }

    private static SettingAutoRecords.ArchiveTopicMode effectiveTopicMode(
            SettingAutoRecords.ArchiveRule rule) {
        return rule.topicMode == null
                ? SettingAutoRecords.ArchiveTopicMode.MERGE : rule.topicMode;
    }

    private static boolean isEnabled(SettingAutoRecords.Automation automation) {
        return automation != null && automation.archive != null && automation.archive.enabled
               && automation.archive.rule != null && automation.archive.rule.targetChatId != 0;
    }

    private static String safeMessage(Throwable failure) {
        if (failure instanceof TelegramRunException telegramFailure
            && telegramFailure.getError().code == 406) {
            return "Telegram rejected the request";
        }
        return StrUtil.blankToDefault(failure.getMessage(), failure.getClass().getSimpleName());
    }

    private record AlbumKey(long telegramId, long chatId, long albumId) {
    }

    private static final class AlbumBuffer {
        private final Set<Long> messageIds = new LinkedHashSet<>();
        private long timerId;
    }

    private record ArchiveWork(List<CloudArchiveRecord> records) {
    }

    private static final class AccountQueue {
        private final Deque<ArchiveWork> live = new ArrayDeque<>();
        private final Deque<ArchiveWork> history = new ArrayDeque<>();

        private void add(ArchiveWork work) {
            if (work.records().getFirst().historyJobId() == null) {
                live.addLast(work);
            } else {
                history.addLast(work);
            }
        }

        private ArchiveWork poll() {
            ArchiveWork work = live.pollFirst();
            return work == null ? history.pollFirst() : work;
        }
    }

    private static final class TopicResolutionFailure extends RuntimeException {
        private final String code;
        private final boolean retryable;

        private TopicResolutionFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        private TopicResolutionFailure(String code,
                                       String message,
                                       boolean retryable,
                                       Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retryable = retryable;
        }

        private String code() {
            return code;
        }

        private boolean retryable() {
            return retryable;
        }
    }
}
