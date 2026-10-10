package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.TdApi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Waits for TDLib's final send updates, including updates arriving before the response. */
final class CloudArchiveDeliveryTracker {
    private static final long CONFIRMATION_TIMEOUT_MILLIS = 120_000L;
    private final Set<Delivery> deliveries = new HashSet<>();

    Future<TdApi.Messages> forward(TelegramGateway gateway, TdApi.ForwardMessages request, Vertx vertx) {
        return forward(gateway, request, vertx, CONFIRMATION_TIMEOUT_MILLIS);
    }

    synchronized Future<TdApi.Messages> forward(TelegramGateway gateway, TdApi.ForwardMessages request,
                                               Vertx vertx, long timeoutMillis) {
        Delivery delivery = new Delivery(request.chatId, vertx);
        deliveries.add(delivery);
        if (vertx != null) {
            delivery.timerId = vertx.setTimer(timeoutMillis, _ -> timeout(delivery));
        }
        try {
            gateway.execute(request).onComplete(result -> {
                synchronized (CloudArchiveDeliveryTracker.this) {
                    if (delivery.promise.future().isComplete()) return;
                    if (result.failed()) {
                        finish(delivery);
                        delivery.promise.tryFail(result.cause());
                        return;
                    }
                    delivery.response = result.result();
                    if (delivery.response != null && delivery.response.messages != null) {
                        for (int index = 0; index < delivery.response.messages.length; index++) {
                            TdApi.Message message = delivery.response.messages[index];
                            if (message != null && delivery.updates.containsKey(message.id)) {
                                delivery.response.messages[index] = delivery.updates.get(message.id);
                            }
                        }
                    }
                    delivery.updates.clear();
                    completeIfSent(delivery);
                    if (!delivery.promise.future().isComplete() && vertx == null) {
                        finish(delivery);
                        delivery.promise.tryFail(new Unconfirmed("No runtime is available to confirm delivery"));
                    }
                }
            });
        } catch (Throwable failure) {
            finish(delivery);
            delivery.promise.tryFail(failure);
        }
        return delivery.promise.future();
    }

    synchronized void onUpdate(TdApi.Object update) {
        TdApi.Message message;
        long oldMessageId;
        if (update instanceof TdApi.UpdateMessageSendSucceeded succeeded) {
            message = succeeded.message;
            oldMessageId = succeeded.oldMessageId;
        } else if (update instanceof TdApi.UpdateMessageSendFailed failed) {
            message = failed.message;
            oldMessageId = failed.oldMessageId;
            if (message != null) {
                TdApi.MessageSendingStateFailed state = new TdApi.MessageSendingStateFailed();
                state.error = failed.error;
                message.sendingState = state;
            }
        } else {
            return;
        }
        if (message == null) return;
        for (Delivery delivery : Set.copyOf(deliveries)) {
            if (delivery.chatId != message.chatId) continue;
            if (delivery.response == null) {
                delivery.updates.put(oldMessageId, message);
            } else if (delivery.response.messages != null) {
                for (int index = 0; index < delivery.response.messages.length; index++) {
                    TdApi.Message pending = delivery.response.messages[index];
                    if (pending != null && pending.id == oldMessageId) {
                        delivery.response.messages[index] = message;
                    }
                }
                completeIfSent(delivery);
            }
        }
    }

    private void completeIfSent(Delivery delivery) {
        if (delivery.response != null && delivery.response.messages != null) {
            for (TdApi.Message message : delivery.response.messages) {
                if (message != null && message.sendingState instanceof TdApi.MessageSendingStatePending) return;
            }
        }
        finish(delivery);
        delivery.promise.tryComplete(delivery.response);
    }

    private synchronized void timeout(Delivery delivery) {
        if (delivery.promise.future().isComplete()) return;
        finish(delivery);
        if (delivery.response == null) {
            delivery.promise.tryFail(new Unconfirmed("Telegram delivery confirmation timed out; review before retrying"));
        } else {
            // Retain confirmed successes. The caller marks only still-pending messages UNKNOWN.
            delivery.promise.tryComplete(delivery.response);
        }
    }

    private void finish(Delivery delivery) {
        deliveries.remove(delivery);
        if (delivery.vertx != null && delivery.timerId != 0) delivery.vertx.cancelTimer(delivery.timerId);
    }

    static final class Unconfirmed extends RuntimeException {
        Unconfirmed(String message) { super(message); }
    }

    private static final class Delivery {
        final long chatId;
        final Vertx vertx;
        final Promise<TdApi.Messages> promise = Promise.promise();
        final Map<Long, TdApi.Message> updates = new HashMap<>();
        TdApi.Messages response;
        long timerId;

        Delivery(long chatId, Vertx vertx) {
            this.chatId = chatId;
            this.vertx = vertx;
        }
    }
}
