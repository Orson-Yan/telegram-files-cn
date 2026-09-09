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
                        : resolveExisting(telegram, key, existing,
                                () -> Future.succeededFuture(source),
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
                        return resolveExisting(telegram, key, existing,
                                () -> sourceTopic(telegram, sourceChatId, sourceTopicId),
                                () -> sourceTopic(telegram, sourceChatId, sourceTopicId)
                                        .compose(source -> createMapping(telegram, telegramId,
                                                sourceChatId, source, targetChatId)));
                    }
                    return sourceTopic(telegram, sourceChatId, sourceTopicId)
                            .compose(source -> createMapping(telegram, telegramId, sourceChatId,
                                    source, targetChatId));
                });
    }

    private static Future<Long> resolveExisting(
            TelegramVerticle telegram,
            String key,
            CloudArchiveTopicMap existing,
            Supplier<Future<TdApi.ForumTopicInfo>> source,
            Supplier<Future<Long>> recreate) {
        if (existing.general()) {
            return validateExisting(telegram, key, existing, source, recreate);
        }
        return DataVerticle.cloudArchiveTopicRepository.targetMappedToAnotherSource(
                        existing.telegramId(), existing.sourceChatId(), existing.sourceTopicId(),
                        existing.targetChatId(), existing.targetTopicId())
                .compose(collision -> collision
                        ? recreate.get()
                        : validateExisting(telegram, key, existing, source, recreate));
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
                                                  Supplier<Future<TdApi.ForumTopicInfo>> source,
                                                  Supplier<Future<Long>> recreate) {
        VerifiedMapping verified = VERIFIED_MAPPINGS.get(key);
        if (verified != null && verified.targetTopicId() == existing.targetTopicId()
            && verified.expiresAt() > System.currentTimeMillis()) {
            return Future.succeededFuture(existing.targetTopicId());
        }
        return telegram.client.execute(new TdApi.GetForumTopic(
                        existing.targetChatId(), Math.toIntExact(existing.targetTopicId())))
                .map(topic -> topic == null ? null : topic.info)
                .recover(failure -> CloudArchiveService.isTopicFailure(failure)
                        ? Future.succeededFuture(null)
                        : Future.failedFuture(failure))
                .compose(target -> target == null
                        ? recreate.get()
                        : source.get()
                                .compose(sourceTopic -> syncTopicMetadata(
                                                telegram, sourceTopic, target)
                                        .compose(_ -> save(existing.telegramId(),
                                                existing.sourceChatId(), sourceTopic,
                                                existing.targetChatId(), target))));
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
        Future<TdApi.ForumTopicInfo> target;
        if (source.isGeneral) {
            target = TelegramTopics.listAll(telegram.client, targetChatId, "")
                    .map(targets -> findGeneralTarget(targets))
                    .compose(found -> found == null
                            ? Future.failedFuture(
                                    "The destination forum General topic is unavailable")
                            : Future.succeededFuture(found));
        } else {
            int color = source.icon == null || source.icon.color == 0
                    ? DEFAULT_TOPIC_COLOR : source.icon.color;
            long customEmojiId = topicCustomEmojiId(source);
            target = telegram.client.execute(new TdApi.CreateForumTopic(
                            targetChatId, topicName(source.name), false,
                            new TdApi.ForumTopicIcon(color, customEmojiId)))
                    .recover(failure -> customEmojiId == 0
                            ? Future.failedFuture(failure)
                            : telegram.client.execute(new TdApi.CreateForumTopic(
                                    targetChatId, topicName(source.name), false,
                                    new TdApi.ForumTopicIcon(color, 0))));
        }
        return target.compose(created -> syncTopicMetadata(telegram, source, created)
                .compose(_ -> save(telegramId, sourceChatId, source, targetChatId, created)));
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

    private static TdApi.ForumTopicInfo findGeneralTarget(List<TdApi.ForumTopic> targets) {
        return targets.stream().map(topic -> topic.info)
                .filter(info -> info != null && info.isGeneral)
                .findFirst().orElse(null);
    }

    private static Future<Void> syncTopicMetadata(TelegramVerticle telegram,
                                                   TdApi.ForumTopicInfo source,
                                                   TdApi.ForumTopicInfo target) {
        String sourceName = topicName(source.name);
        String targetName = topicName(target.name);
        long sourceEmoji = topicCustomEmojiId(source);
        long targetEmoji = topicCustomEmojiId(target);
        boolean editName = !sourceName.equals(targetName);
        boolean editEmoji = !source.isGeneral && sourceEmoji != targetEmoji;

        Future<Void> chain = Future.succeededFuture();
        if (editName || editEmoji) {
            chain = telegram.client.<TdApi.Ok>execute(new TdApi.EditForumTopic(
                            target.chatId, target.forumTopicId,
                            editName ? sourceName : "", editEmoji, sourceEmoji))
                    .<Void>mapEmpty()
                    .recover(failure -> editEmoji
                            ? telegram.client.<TdApi.Ok>execute(new TdApi.EditForumTopic(
                                    target.chatId, target.forumTopicId,
                                    editName ? sourceName : "", false, 0)).<Void>mapEmpty()
                            : Future.failedFuture(failure))
                    .onSuccess(_ -> {
                        if (editName) {
                            target.name = sourceName;
                        }
                        if (editEmoji && target.icon != null) {
                            target.icon.customEmojiId = sourceEmoji;
                        }
                    });
        }
        if (source.isGeneral && source.isHidden != target.isHidden) {
            chain = chain.compose(_ -> telegram.client.<TdApi.Ok>execute(
                            new TdApi.ToggleGeneralForumTopicIsHidden(
                                    target.chatId, source.isHidden))
                    .<Void>mapEmpty()
                    .onSuccess(_ -> target.isHidden = source.isHidden));
        } else if (!source.isGeneral && source.isClosed != target.isClosed) {
            chain = chain.compose(_ -> telegram.client.<TdApi.Ok>execute(
                            new TdApi.ToggleForumTopicIsClosed(
                                    target.chatId, target.forumTopicId, source.isClosed))
                    .<Void>mapEmpty()
                    .onSuccess(_ -> target.isClosed = source.isClosed));
        }
        return chain;
    }

    private static long topicCustomEmojiId(TdApi.ForumTopicInfo topic) {
        return topic == null || topic.icon == null ? 0 : topic.icon.customEmojiId;
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
