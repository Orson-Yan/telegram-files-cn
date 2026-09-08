package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.SettingAutoRecords;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Executes Telegram server-side copies/forwards without downloading media locally. */
public final class CloudArchiveService {

    private CloudArchiveService() {
    }

    public static Future<Map<Long, Long>> archive(TelegramVerticle telegram,
                                                   long sourceChatId,
                                                   List<Long> sourceMessageIds,
                                                   SettingAutoRecords.ArchiveRule rule) {
        Objects.requireNonNull(telegram, "telegram");
        Objects.requireNonNull(rule, "rule");
        if (rule.targetChatId == 0) {
            return Future.failedFuture(new Rejected("TARGET_REQUIRED", "A destination chat is required"));
        }
        if (sourceChatId == rule.targetChatId) {
            return Future.failedFuture(new Rejected("SAME_CHAT", "Source and destination chats must be different"));
        }

        long[] ids = sourceMessageIds.stream()
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .distinct()
                .sorted()
                .limit(100)
                .toArray();
        if (ids.length == 0) {
            return Future.failedFuture(new Rejected("MESSAGE_REQUIRED", "No source messages were provided"));
        }

        TdApi.Chat sourceChat = telegram.getChat(sourceChatId);
        if (sourceChat != null && sourceChat.hasProtectedContent) {
            return Future.failedFuture(new Rejected(
                    "PROTECTED_CONTENT",
                    "The source chat protects its content from copying and forwarding"
            ));
        }

        return telegram.client.execute(new TdApi.GetMessages(sourceChatId, ids))
                .compose(messages -> validateMessages(telegram.client, sourceChatId, ids, messages, rule)
                        .map(_ -> messages))
                .compose(messages -> {
                    TdApi.MessageSendOptions options = new TdApi.MessageSendOptions();
                    options.disableNotification = rule.disableNotification;
                    options.fromBackground = true;
                    long telegramId = telegram.telegramRecord == null ? 0L : telegram.telegramRecord.id();
                    options.sendingId = nonZeroHash(telegramId, sourceChatId, rule.targetChatId, ids[0]);

                    TdApi.ForwardMessages request = new TdApi.ForwardMessages(
                            rule.targetChatId,
                            null,
                            sourceChatId,
                            ids,
                            options,
                            rule.mode != SettingAutoRecords.ArchiveMode.FORWARD,
                            !rule.preserveCaption
                    );
                    return telegram.client.execute(request);
                })
                .map(result -> mapTargets(ids, result));
    }

    private static Future<Void> validateMessages(TelegramGateway gateway,
                                                 long sourceChatId,
                                                 long[] ids,
                                                 TdApi.Messages messages,
                                                 SettingAutoRecords.ArchiveRule rule) {
        if (messages == null || messages.messages == null || messages.messages.length != ids.length) {
            return Future.failedFuture(new Rejected("SOURCE_MESSAGE_MISSING", "One or more source messages are unavailable"));
        }
        for (TdApi.Message message : messages.messages) {
            if (message == null) {
                return Future.failedFuture(new Rejected("SOURCE_MESSAGE_MISSING", "One or more source messages are unavailable"));
            }
        }

        List<Future<TdApi.MessageProperties>> futures = Arrays.stream(ids)
                .mapToObj(id -> gateway.execute(new TdApi.GetMessageProperties(sourceChatId, id)))
                .toList();
        return Future.all(futures).compose(properties -> {
            for (int index = 0; index < ids.length; index++) {
                TdApi.MessageProperties value = properties.resultAt(index);
                if (value.hasProtectedContentByCurrentUser || value.hasProtectedContentByOtherUser) {
                    return Future.failedFuture(new Rejected(
                            "PROTECTED_CONTENT",
                            "Message %d is protected".formatted(ids[index])
                    ));
                }
                boolean allowed = rule.mode == SettingAutoRecords.ArchiveMode.FORWARD
                        ? value.canBeForwarded
                        : value.canBeCopied;
                if (!allowed) {
                    return Future.failedFuture(new Rejected(
                            rule.mode == SettingAutoRecords.ArchiveMode.FORWARD
                                    ? "FORWARD_RESTRICTED"
                                    : "COPY_RESTRICTED",
                            "Message %d can't be archived in the selected mode".formatted(ids[index])
                    ));
                }
            }
            return Future.succeededFuture();
        });
    }

    private static Map<Long, Long> mapTargets(long[] sourceIds, TdApi.Messages result) {
        Map<Long, Long> targets = new LinkedHashMap<>();
        TdApi.Message[] messages = result == null ? null : result.messages;
        for (int index = 0; index < sourceIds.length; index++) {
            TdApi.Message message = messages != null && index < messages.length ? messages[index] : null;
            targets.put(sourceIds[index], message == null ? 0L : message.id);
        }
        return targets;
    }

    public static Future<JsonObject> validate(TelegramVerticle telegram,
                                              long sourceChatId,
                                              SettingAutoRecords.ArchiveRule rule) {
        JsonArray warnings = new JsonArray();
        if (sourceChatId == 0 || rule == null || rule.targetChatId == 0) {
            return Future.succeededFuture(new JsonObject()
                    .put("valid", false)
                    .put("code", "CHAT_REQUIRED")
                    .put("message", "Choose both a source and destination chat")
                    .put("warnings", warnings));
        }
        if (sourceChatId == rule.targetChatId) {
            return Future.succeededFuture(new JsonObject()
                    .put("valid", false)
                    .put("code", "SAME_CHAT")
                    .put("message", "Source and destination chats must be different")
                    .put("warnings", warnings));
        }

        TdApi.Chat source = telegram.getChat(sourceChatId);
        TdApi.Chat target = telegram.getChat(rule.targetChatId);
        if (source == null || target == null) {
            return Future.succeededFuture(new JsonObject()
                    .put("valid", false)
                    .put("code", "CHAT_NOT_LOADED")
                    .put("message", "Source or destination chat isn't available to this account")
                    .put("warnings", warnings));
        }
        if (source.hasProtectedContent) {
            return Future.succeededFuture(new JsonObject()
                    .put("valid", false)
                    .put("code", "PROTECTED_CONTENT")
                    .put("message", "The source chat protects its content")
                    .put("warnings", warnings));
        }
        if (target.hasProtectedContent) {
            warnings.add("The destination protects archived messages from later forwarding or saving");
        }
        warnings.add("Use Send real test to confirm that this account can write to the destination chat");
        if (source.lastMessage == null) {
            warnings.add("The source chat has no recent message to test");
            return Future.succeededFuture(new JsonObject()
                    .put("valid", true)
                    .put("warnings", warnings));
        }

        return telegram.client.execute(new TdApi.GetMessageProperties(sourceChatId, source.lastMessage.id))
                .map(properties -> {
                    boolean allowed = rule.mode == SettingAutoRecords.ArchiveMode.FORWARD
                            ? properties.canBeForwarded
                            : properties.canBeCopied;
                    return new JsonObject()
                            .put("valid", allowed)
                            .put("code", allowed ? "OK" : rule.mode == SettingAutoRecords.ArchiveMode.FORWARD
                                    ? "FORWARD_RESTRICTED" : "COPY_RESTRICTED")
                            .put("message", allowed
                                    ? "The latest source message can be archived"
                                    : "The latest source message can't be archived in the selected mode")
                            .put("warnings", warnings);
                });
    }

    public static boolean isRetryable(Throwable failure) {
        if (failure instanceof TimeoutException) {
            return true;
        }
        if (failure instanceof TelegramRunException telegramFailure) {
            TdApi.Error error = telegramFailure.getError();
            String message = error.message == null ? "" : error.message;
            return error.code == 429 || error.code >= 500
                   || message.contains("FLOOD_WAIT") || message.contains("TIMEOUT");
        }
        return false;
    }

    public static String errorCode(Throwable failure) {
        if (failure instanceof Rejected rejected) {
            return rejected.code();
        }
        if (failure instanceof TelegramRunException telegramFailure) {
            return "TELEGRAM_%d".formatted(telegramFailure.getError().code);
        }
        if (failure instanceof TimeoutException) {
            return "TIMEOUT";
        }
        return "UNEXPECTED_ERROR";
    }

    private static int nonZeroHash(long... values) {
        int result = Arrays.hashCode(values);
        return result == 0 ? 1 : result;
    }

    public static final class Rejected extends RuntimeException {
        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
