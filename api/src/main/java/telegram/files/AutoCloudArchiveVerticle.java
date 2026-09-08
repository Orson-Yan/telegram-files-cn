package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

/** Watches new Telegram messages and archives matching messages inside Telegram. */
public final class AutoCloudArchiveVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    private static final long RETRY_SCAN_INTERVAL = 5_000L;

    private static final long ALBUM_DEBOUNCE = 1_800L;

    private static final int SCAN_LIMIT = 200;

    private static final int MAX_ATTEMPTS = 8;

    private static final long MAX_MESSAGES_PER_MINUTE = 20L;

    private static final long MAX_MESSAGES_PER_HOUR = 300L;

    private static final long MAX_MESSAGES_PER_DAY = 1_000L;

    private final SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();

    private final Map<AlbumKey, AlbumBuffer> albumBuffers = new HashMap<>();

    private final Map<Long, Deque<ArchiveWork>> queues = new HashMap<>();

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
        if (message == null || message.isOutgoing || !matchesTopic(message, automation.archive.rule.sourceTopicId)) {
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

        List<Future<Boolean>> inserts = messages.stream()
                .map(message -> DataVerticle.cloudArchiveRepository.enqueue(
                        automation.telegramId,
                        automation.chatId,
                        rule.sourceTopicId,
                        message.id,
                        message.mediaAlbumId,
                        rule.targetChatId,
                        rule.targetTopicId,
                        TdApiHelp.getFileUniqueId(message),
                        effectiveMode(rule).name()
                ))
                .toList();
        Future.all(inserts)
                .onSuccess(_ -> scanDue())
                .onFailure(failure -> log.error(failure, "Failed to enqueue cloud archive messages"));
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
                    for (CloudArchiveRecord record : records) {
                        if (queuedRecordIds.contains(record.id())) {
                            continue;
                        }
                        String key = record.sourceAlbumId() == 0
                                ? "message:" + record.id()
                                : "%d:%d:%d:%d:%d:%d:%s".formatted(
                                        record.telegramId(), record.sourceChatId(), record.sourceAlbumId(),
                                        record.sourceTopicId(), record.targetChatId(), record.targetTopicId(), record.mode());
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
            return DataVerticle.cloudArchiveRepository.countPending(job.telegramId()).compose(pending -> {
                // Keep historical scans behind delivery so a large channel can't flood the durable queue.
                if (pending >= 200) {
                    return Future.succeededFuture();
                }
                int remaining = Math.max(0, job.maxMessages() - job.scannedCount());
                if (remaining == 0) {
                    return DataVerticle.cloudArchiveHistoryRepository.advance(
                            job.id(), job.fromMessageId(), 0, 0, 0, true);
                }
                int pageSize = Math.min(50, remaining);
                return historyMessages(telegram, job, pageSize).compose(messages -> {
                List<TdApi.Message> candidates = Arrays.stream(messages)
                        .filter(Objects::nonNull)
                        .filter(message -> !message.isOutgoing)
                        .filter(message -> matchesTopic(message, job.sourceTopicId()))
                        .filter(message -> matches(message, rule))
                        .toList();
                List<Future<Boolean>> inserts = candidates.stream()
                        .map(message -> DataVerticle.cloudArchiveRepository.enqueue(
                                job.telegramId(),
                                job.sourceChatId(),
                                job.sourceTopicId(),
                                message.id,
                                message.mediaAlbumId,
                                job.targetChatId(),
                                job.targetTopicId(),
                                TdApiHelp.getFileUniqueId(message),
                                effectiveMode(rule).name()))
                        .toList();
                return Future.all(inserts).compose(results -> {
                    int queued = 0;
                    for (int index = 0; index < inserts.size(); index++) {
                        if (Boolean.TRUE.equals(results.resultAt(index))) {
                            queued++;
                        }
                    }
                    long cursor = Arrays.stream(messages)
                            .filter(Objects::nonNull)
                            .mapToLong(message -> message.id)
                            .min()
                            .stream()
                            .map(messageId -> Math.max(0L, messageId - 1L))
                            .findFirst()
                            .orElse(job.fromMessageId());
                    boolean completed = messages.length == 0
                                        || job.scannedCount() + messages.length >= job.maxMessages();
                    return DataVerticle.cloudArchiveHistoryRepository.advance(
                            job.id(), cursor, messages.length, candidates.size(), queued, completed);
                }).onSuccess(_ -> scanDue());
                });
            }).recover(failure -> DataVerticle.cloudArchiveHistoryRepository.fail(
                        job.id(), safeMessage(failure)));
        });
    }

    private Future<TdApi.Message[]> historyMessages(TelegramVerticle telegram,
                                                     CloudArchiveHistoryJob job,
                                                     int limit) {
        if (job.sourceTopicId() != 0) {
            TdApi.SearchChatMessages request = new TdApi.SearchChatMessages(
                    job.sourceChatId(),
                    new TdApi.MessageTopicForum((int) job.sourceTopicId()),
                    "",
                    null,
                    job.fromMessageId(),
                    0,
                    limit,
                    null
            );
            return telegram.client.execute(request)
                    .map(found -> found == null || found.messages == null
                            ? new TdApi.Message[0] : found.messages);
        }
        return telegram.client.execute(new TdApi.GetChatHistory(
                        job.sourceChatId(), job.fromMessageId(), 0, limit, false))
                .map(found -> found == null || found.messages == null
                        ? new TdApi.Message[0] : found.messages);
    }

    private void queue(List<CloudArchiveRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        records.forEach(record -> queuedRecordIds.add(record.id()));
        long telegramId = records.getFirst().telegramId();
        queues.computeIfAbsent(telegramId, _ -> new ArrayDeque<>())
                .addLast(new ArchiveWork(List.copyOf(records)));
        drain(telegramId);
    }

    private void drain(long telegramId) {
        if (busyAccounts.contains(telegramId)) {
            return;
        }
        Deque<ArchiveWork> queue = queues.get(telegramId);
        ArchiveWork work = queue == null ? null : queue.pollFirst();
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
        if (!isEnabled(automation) || automation.archive.rule.targetChatId != first.targetChatId()
            || automation.archive.rule.sourceTopicId != first.sourceTopicId()
            || automation.archive.rule.targetTopicId != first.targetTopicId()) {
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
                                "The conservative account safety limit paused cloud archiving");
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

            SettingAutoRecords.ArchiveRule rule = copyRule(automation.archive.rule, first);
            List<Long> ids = claimed.stream().map(CloudArchiveRecord::sourceMessageId).sorted().toList();
            return CloudArchiveService.archive(telegram, first.sourceChatId(), ids, rule)
                    .compose(targets -> completeAll(claimed, targets))
                    .recover(failure -> handleFailure(claimed, failure));
        });
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
        if (failure instanceof CloudArchiveService.Rejected rejected) {
            List<Future<Void>> updates = records.stream()
                    .map(record -> DataVerticle.cloudArchiveRepository.skip(
                            record.id(), rejected.code(), rejected.getMessage()))
                    .toList();
            return Future.all(updates).mapEmpty();
        }
        boolean retryable = CloudArchiveService.isRetryable(failure);
        long telegramWaitMillis = CloudArchiveService.retryAfterMillis(failure);
        if (telegramWaitMillis > 0 && !records.isEmpty()) {
            accountCooldownUntil.merge(records.getFirst().telegramId(),
                    System.currentTimeMillis() + telegramWaitMillis, Math::max);
        }
        return failAll(records, retryable,
                telegramWaitMillis > 0 ? "TELEGRAM_WAIT" : CloudArchiveService.errorCode(failure),
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
        copy.mode = SettingAutoRecords.ArchiveMode.valueOf(record.mode());
        copy.scope = configured.scope;
        copy.fileTypes = configured.fileTypes;
        copy.query = configured.query;
        copy.filterExpr = configured.filterExpr;
        copy.preserveCaption = configured.preserveCaption;
        copy.disableNotification = configured.disableNotification;
        return copy;
    }

    private static SettingAutoRecords.ArchiveMode effectiveMode(SettingAutoRecords.ArchiveRule rule) {
        return rule.mode == null ? SettingAutoRecords.ArchiveMode.COPY : rule.mode;
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
}
