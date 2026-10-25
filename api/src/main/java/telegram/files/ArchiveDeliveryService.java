package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.impl.ArchiveOperationsRepository;

import java.util.Map;

/** Journals responses before releasing the tracker, and handles updates after tracker timeout. */
public final class ArchiveDeliveryService {
    private static ArchiveOperationsRepository store() { return DataVerticle.archiveOperations; }

    static Future<TdApi.Messages> forward(TelegramVerticle telegram, CloudArchiveDeliveryTracker tracker,
                                           TdApi.ForwardMessages request, io.vertx.core.Vertx vertx) {
        if (store() == null) return tracker.forward(telegram.client, request, vertx);
        return store().begin(cn.hutool.core.convert.Convert.toLong(telegram.getId()), request.fromChatId, request.chatId, request.messageIds)
                .compose(attempts -> {
                    TelegramGateway journaled = new TelegramGateway() {
                        public void initialize(org.drinkless.tdlib.Client.ResultHandler handler,
                                               org.drinkless.tdlib.Client.ExceptionHandler updateErrors,
                                               org.drinkless.tdlib.Client.ExceptionHandler errors) {
                            telegram.client.initialize(handler, updateErrors, errors);
                        }
                        public <T extends TdApi.Object> Future<T> execute(TdApi.Function<T> function, boolean ignore) {
                            return execute(function);
                        }
                        public void send(TdApi.Function<?> function, org.drinkless.tdlib.Client.ResultHandler handler) {
                            telegram.client.send(function, handler);
                        }
                        @Override
                        @SuppressWarnings("unchecked")
                        public <T extends TdApi.Object> Future<T> execute(TdApi.Function<T> function) {
                            return telegram.client.execute(function).compose(result -> {
                                if (!(result instanceof TdApi.Messages messages)) return Future.succeededFuture(result);
                                // This runs even if the original 120-second wait has already expired.
                                return observe(request, attempts, messages).map(result)
                                        .recover(_ -> Future.failedFuture(new CloudArchiveDeliveryTracker.Unconfirmed(
                                                "Delivery evidence could not be saved; verify before retrying")));
                            });
                        }
                    };
                    return tracker.forward(journaled, request, vertx)
                            .compose(messages -> observe(request, attempts, messages).map(messages)
                                    .recover(_ -> Future.failedFuture(new CloudArchiveDeliveryTracker.Unconfirmed(
                                            "Delivery evidence could not be saved; verify before retrying"))));
                });
    }

    private static Future<Void> observe(TdApi.ForwardMessages request, Map<Long, String> attempts, TdApi.Messages messages) {
        Future<Void> result = Future.succeededFuture();
        for (int i = 0; i < request.messageIds.length; i++) {
            String attempt = attempts.get(request.messageIds[i]);
            TdApi.Message message = messages == null || messages.messages == null || i >= messages.messages.length ? null : messages.messages[i];
            if (attempt == null || message == null) continue;
            result = result.compose(_ -> observe(attempt, message));
        }
        return result;
    }

    private static Future<Void> observe(String attempt, TdApi.Message message) {
        if (message.sendingState instanceof TdApi.MessageSendingStatePending) return store().pending(attempt, message.id);
        if (message.sendingState instanceof TdApi.MessageSendingStateFailed failed) {
            return store().failed(attempt, failed.error == null ? "Telegram delivery failed" : failed.error.message);
        }
        if (message.id == 0) return Future.succeededFuture();
        return store().confirm(attempt, message.id, message.date > 0 ? message.date * 1000L : System.currentTimeMillis());
    }

    static Future<Void> onUpdate(long account, TdApi.Object update) {
        if (store() == null) return Future.succeededFuture();
        TdApi.Message message;
        long previous;
        if (update instanceof TdApi.UpdateMessageSendSucceeded sent) {
            message = sent.message; previous = sent.oldMessageId;
        } else if (update instanceof TdApi.UpdateMessageSendFailed failed) {
            message = failed.message; previous = failed.oldMessageId;
            if (message != null) {
                TdApi.MessageSendingStateFailed state = new TdApi.MessageSendingStateFailed();
                state.error = failed.error; message.sendingState = state;
            }
        } else return Future.succeededFuture();
        if (message == null) return Future.succeededFuture();
        String state = message.sendingState instanceof TdApi.MessageSendingStateFailed ? "FAILED" : "CONFIRMED";
        String error = message.sendingState instanceof TdApi.MessageSendingStateFailed failed && failed.error != null ? failed.error.message : null;
        return store().rememberUpdate(account, message.chatId, previous, message.id, message.date > 0 ? message.date * 1000L : System.currentTimeMillis(), state, error)
                .compose(_ -> store().byTemporaryId(account, message.chatId, previous)).compose(attempts -> {
            Future<Void> result = Future.succeededFuture();
            for (JsonObject attempt : attempts) result = result.compose(_ -> observe(attempt.getString("id"), message));
            return result;
        });
    }

    public static Future<JsonObject> reconcile(String recordId) {
        return store().find(recordId).compose(record -> {
            if (record == null) return Future.failedFuture("Record not found");
            if (!"UNKNOWN".equals(record.status())) return Future.succeededFuture(JsonObject.of("status", record.status()));
            TelegramVerticle telegram = TelegramVerticles.get(record.telegramId()).orElse(null);
            if (telegram == null) return Future.succeededFuture(unresolved("Account is unavailable"));
            return store().attempts(recordId).compose(attempts -> {
                if (attempts.isEmpty()) return Future.succeededFuture(unresolved("No saved delivery evidence; manual verification required"));
                JsonObject latest = attempts.getJsonObject(0);
                long temporary = number(latest, "temporary_message_id");
                if ("CONFIRMED".equals(latest.getString("state")) && number(latest, "target_message_id") != 0) {
                    return store().confirm(latest.getString("id"), number(latest, "target_message_id"), number(latest, "confirmed_at"))
                            .map(JsonObject.of("status", "COMPLETED"));
                }
                if (temporary == 0 || !"PENDING".equals(latest.getString("state"))) {
                    return Future.succeededFuture(unresolved("No queryable temporary message; manual verification required"));
                }
                return store().checked(latest.getString("id"))
                        .compose(_ -> telegram.client.execute(new TdApi.GetMessage(record.targetChatId(), temporary))
                                .timeout(10, java.util.concurrent.TimeUnit.SECONDS))
                        .compose(message -> observe(latest.getString("id"), message).map(_ -> JsonObject.of("status",
                                message.sendingState == null ? "COMPLETED" : message.sendingState instanceof TdApi.MessageSendingStateFailed ? "FAILED" : "UNKNOWN")))
                        // A missing temporary ID is not evidence that the send failed.
                        .recover(_ -> Future.succeededFuture(unresolved("Temporary message is unavailable; manual verification required")));
            });
        });
    }

    public static Future<Void> resolve(String id, JsonObject body) {
        String action = body.getString("action", "");
        String reason = body.getString("reason", "").trim();
        if (reason.isEmpty() || reason.length() > 1024) return Future.failedFuture("A reason of 1 to 1024 characters is required");
        return store().find(id).compose(record -> {
            if (record == null || !("UNKNOWN".equals(record.status()) || "FAILED".equals(record.status()))) {
                return Future.failedFuture("Only unconfirmed or failed records can be resolved");
            }
            if ("SKIP".equals(action)) return store().manualSkip(record, reason);
            if (!"CONFIRM_SENT".equals(action)) return Future.failedFuture("Invalid resolution action");
            TelegramVerticle telegram = TelegramVerticles.get(record.telegramId()).orElse(null);
            if (telegram == null) return Future.failedFuture("Account is unavailable");
            String link = body.getString("targetMessage", "").trim();
            Future<TdApi.Message> message;
            if (link.startsWith("https://t.me/") || link.startsWith("http://t.me/")) {
                message = telegram.client.execute(new TdApi.GetMessageLinkInfo(link)).compose(info -> info.message == null
                        ? Future.failedFuture("Target message could not be read") : Future.succeededFuture(info.message));
            } else {
                try { message = telegram.client.execute(new TdApi.GetMessage(record.targetChatId(), Long.parseLong(link))); }
                catch (NumberFormatException ignored) { return Future.failedFuture("Enter the destination message link or ID"); }
            }
            return message.compose(value -> {
                if (value.chatId != record.targetChatId() || value.sendingState != null || value.id == 0) {
                    return Future.failedFuture("Message must be successfully sent in this rule's destination");
                }
                return store().manualComplete(record, value.id, value.date * 1000L, reason);
            });
        });
    }

    private static JsonObject unresolved(String reason) { return JsonObject.of("status", "UNKNOWN", "reason", reason); }
    private static long number(JsonObject value, String name) { return ((Number) value.getValue(name, 0L)).longValue(); }
    private ArchiveDeliveryService() {}
}
