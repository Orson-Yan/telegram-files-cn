package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.CloudArchiveTopicMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Creates and persistently reuses destination topics for PRESERVE archive rules. */
final class CloudArchiveTopicService {
    private static final int DEFAULT_TOPIC_COLOR = 0x6FB9F0;
    private static final long VERIFY_TTL_MILLIS = 5 * 60 * 1_000L;
    private static final Map<String, Future<Long>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, VerifiedMapping> VERIFIED_MAPPINGS = new ConcurrentHashMap<>();

    private CloudArchiveTopicService() {
    }

    static Future<Long> resolve(TelegramVerticle telegram,
                                long sourceChatId,
                                long sourceTopicId,
                                long targetChatId) {
        if (sourceTopicId == 0) {
            return Future.succeededFuture(0L);
        }
        long telegramId = telegram.telegramRecord.id();
        String key = telegramId + ":" + sourceChatId + ":" + sourceTopicId + ":" + targetChatId;
        return singleFlight(key, () -> remember(key, resolveUncached(
                telegram, key, telegramId, sourceChatId, sourceTopicId, targetChatId)));
    }

    static Future<Long> resolve(TelegramVerticle telegram,
                                long sourceChatId,
                                TdApi.ForumTopicInfo source,
                                long targetChatId) {
        Objects.requireNonNull(source, "source");
        long telegramId = telegram.telegramRecord.id();
        String key = telegramId + ":" + sourceChatId + ":" + source.forumTopicId + ":" + targetChatId;
        return singleFlight(key, () -> remember(key, DataVerticle.cloudArchiveTopicRepository
                .find(telegramId, sourceChatId, source.forumTopicId, targetChatId)
                .compose(existing -> existing == null
                        ? createMapping(telegram, telegramId, sourceChatId, source, targetChatId)
                        : validateExisting(telegram, key, existing,
                                () -> createMapping(telegram, telegramId, sourceChatId,
                                        source, targetChatId)))));
    }

    static void invalidate(long telegramId,
                           long sourceChatId,
                           long sourceTopicId,
                           long targetChatId) {
        VERIFIED_MAPPINGS.remove(
                telegramId + ":" + sourceChatId + ":" + sourceTopicId + ":" + targetChatId);
    }

    private static Future<Long> singleFlight(String key, Supplier<Future<Long>> action) {
        Future<Long> active = IN_FLIGHT.get(key);
        if (active != null) {
            return active;
        }
        Promise<Long> promise = Promise.promise();
        Future<Long> created = promise.future();
        Future<Long> raced = IN_FLIGHT.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }
        created.onComplete(_ -> IN_FLIGHT.remove(key, created));
        try {
            action.get().onComplete(promise);
        } catch (Throwable failure) {
            promise.fail(failure);
        }
        return created;
    }

    private static Future<Long> resolveUncached(TelegramVerticle telegram,
                                                 String key,
                                                 long telegramId,
                                                 long sourceChatId,
                                                 long sourceTopicId,
                                                 long targetChatId) {
        return DataVerticle.cloudArchiveTopicRepository
                .find(telegramId, sourceChatId, sourceTopicId, targetChatId)
                .compose(existing -> {
                    if (existing != null) {
                        return validateExisting(telegram, key, existing,
                                () -> sourceTopic(telegram, sourceChatId, sourceTopicId)
                                        .compose(source -> createMapping(telegram, telegramId,
                                                sourceChatId, source, targetChatId)));
                    }
                    return sourceTopic(telegram, sourceChatId, sourceTopicId)
                            .compose(source -> createMapping(telegram, telegramId, sourceChatId,
                                    source, targetChatId));
                });
    }

    private static Future<TdApi.ForumTopicInfo> sourceTopic(TelegramVerticle telegram,
                                                             long sourceChatId,
                                                             long sourceTopicId) {
        return telegram.client.execute(new TdApi.GetForumTopic(
                        sourceChatId, Math.toIntExact(sourceTopicId)))
                .compose(topic -> topic == null || topic.info == null
                        ? Future.failedFuture("The source forum topic is unavailable")
                        : Future.succeededFuture(topic.info));
    }

    private static Future<Long> validateExisting(TelegramVerticle telegram,
                                                  String key,
                                                  CloudArchiveTopicMap existing,
                                                  Supplier<Future<Long>> recreate) {
        VerifiedMapping verified = VERIFIED_MAPPINGS.get(key);
        if (verified != null && verified.targetTopicId() == existing.targetTopicId()
            && verified.expiresAt() > System.currentTimeMillis()) {
            return Future.succeededFuture(existing.targetTopicId());
        }
        return telegram.client.execute(new TdApi.GetForumTopic(
                        existing.targetChatId(), Math.toIntExact(existing.targetTopicId())))
                .map(topic -> topic != null && topic.info != null)
                .recover(failure -> CloudArchiveService.isTopicFailure(failure)
                        ? Future.succeededFuture(false)
                        : Future.failedFuture(failure))
                .compose(valid -> valid
                        ? Future.succeededFuture(existing.targetTopicId())
                        : recreate.get());
    }

    private static Future<Long> remember(String key, Future<Long> resolution) {
        return resolution.onSuccess(targetTopicId -> VERIFIED_MAPPINGS.put(key,
                new VerifiedMapping(targetTopicId,
                        System.currentTimeMillis() + VERIFY_TTL_MILLIS)));
    }

    private static Future<Long> createMapping(TelegramVerticle telegram,
                                               long telegramId,
                                               long sourceChatId,
                                               TdApi.ForumTopicInfo source,
                                               long targetChatId) {
        return TelegramTopics.listAll(telegram.client, targetChatId, "")
                .compose(targets -> {
                    TdApi.ForumTopicInfo target = findTarget(source, targets);
                    if (target != null) {
                        return save(telegramId, sourceChatId, source, targetChatId, target);
                    }
                    if (source.isGeneral) {
                        return Future.failedFuture("The destination forum General topic is unavailable");
                    }
                    int color = source.icon == null || source.icon.color == 0
                            ? DEFAULT_TOPIC_COLOR : source.icon.color;
                    return telegram.client.execute(new TdApi.CreateForumTopic(
                                    targetChatId, topicName(source.name), false,
                                    new TdApi.ForumTopicIcon(color, 0)))
                            .compose(created -> save(telegramId, sourceChatId, source,
                                    targetChatId, created));
                });
    }

    private static Future<Long> save(long telegramId,
                                     long sourceChatId,
                                     TdApi.ForumTopicInfo source,
                                     long targetChatId,
                                     TdApi.ForumTopicInfo target) {
        if (target == null) {
            return Future.failedFuture("Telegram didn't return the created forum topic");
        }
        return DataVerticle.cloudArchiveTopicRepository.save(
                        telegramId, sourceChatId, source.forumTopicId, topicName(source.name),
                        targetChatId, target.forumTopicId, topicName(target.name), source.isGeneral)
                .map(CloudArchiveTopicMap::targetTopicId);
    }

    private static TdApi.ForumTopicInfo findTarget(TdApi.ForumTopicInfo source,
                                                    List<TdApi.ForumTopic> targets) {
        if (source.isGeneral) {
            return targets.stream().map(topic -> topic.info)
                    .filter(info -> info.isGeneral).findFirst().orElse(null);
        }
        return targets.stream().map(topic -> topic.info)
                .filter(info -> !info.isGeneral && topicName(info.name).equals(topicName(source.name)))
                .findFirst().orElse(null);
    }

    private static String topicName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty()) {
            value = "Archived topic";
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private record VerifiedMapping(long targetTopicId, long expiresAt) {
    }
}
