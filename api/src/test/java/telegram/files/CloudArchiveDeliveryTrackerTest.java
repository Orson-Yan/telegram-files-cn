package telegram.files;

import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(VertxExtension.class)
class CloudArchiveDeliveryTrackerTest {
    @Test
    void waitsForEveryFinalIdAndRoutesSendUpdatesThroughTheHandler(Vertx vertx, VertxTestContext context) {
        var tracker = new CloudArchiveDeliveryTracker();
        var gateway = new ScriptedTelegramGateway(_ -> messages(pending(-1), pending(-2)));
        var request = request();
        var future = tracker.forward(gateway, request, vertx);
        assertFalse(future.isComplete());
        var handler = new TelegramUpdateHandler();
        handler.setOnMessageSendUpdated(tracker::onUpdate);
        handler.onResult(new TdApi.UpdateMessageSendSucceeded(sent(9002), -2));
        assertFalse(future.isComplete());
        handler.onResult(new TdApi.UpdateMessageSendSucceeded(sent(9001), -1));
        future.onComplete(context.succeeding(result -> context.verify(() -> {
            assertEquals(9001, result.messages[0].id);
            assertEquals(9002, result.messages[1].id);
            context.completeNow();
        })));
    }

    @Test
    void retainsSuccessUpdateArrivingBeforeForwardResponse(Vertx vertx, VertxTestContext context) {
        var tracker = new CloudArchiveDeliveryTracker();
        TelegramGateway gateway = mock(TelegramGateway.class);
        var request = request();
        Promise<TdApi.Messages> response = Promise.promise();
        when(gateway.execute(request)).thenReturn(response.future());
        var future = tracker.forward(gateway, request, vertx);
        tracker.onUpdate(new TdApi.UpdateMessageSendSucceeded(sent(9001), -1));
        response.complete(messages(pending(-1)));
        future.onComplete(context.succeeding(result -> context.verify(() -> {
            assertEquals(9001, result.messages[0].id);
            context.completeNow();
        })));
    }

    @Test
    void timeoutKeepsConfirmedMessagesAndLeavesPendingOnesUnconfirmed(Vertx vertx, VertxTestContext context) {
        var tracker = new CloudArchiveDeliveryTracker();
        var gateway = new ScriptedTelegramGateway(_ -> messages(pending(-1), pending(-2)));
        var future = tracker.forward(gateway, request(), vertx, 100);
        tracker.onUpdate(new TdApi.UpdateMessageSendSucceeded(sent(9001), -1));
        future.onComplete(context.succeeding(result -> context.verify(() -> {
            assertEquals(9001, result.messages[0].id);
            assertInstanceOf(TdApi.MessageSendingStatePending.class, result.messages[1].sendingState);
            context.completeNow();
        })));
    }

    @Test
    void failureUpdateCompletesAsFailedInsteadOfSuccessfulTemporaryId(Vertx vertx, VertxTestContext context) {
        var tracker = new CloudArchiveDeliveryTracker();
        var gateway = new ScriptedTelegramGateway(_ -> messages(pending(-1)));
        var future = tracker.forward(gateway, request(), vertx);
        var update = new TdApi.UpdateMessageSendFailed();
        update.message = sent(-1);
        update.oldMessageId = -1;
        update.error = new TdApi.Error(429, "FLOOD_WAIT_5");
        tracker.onUpdate(update);
        future.onComplete(context.succeeding(result -> context.verify(() -> {
            var failed = assertInstanceOf(TdApi.MessageSendingStateFailed.class, result.messages[0].sendingState);
            assertEquals(429, failed.error.code);
            context.completeNow();
        })));
    }

    @Test
    void sameTemporaryIdInAnotherChatCannotConfirmThisDelivery(Vertx vertx, VertxTestContext context) {
        var tracker = new CloudArchiveDeliveryTracker();
        var gateway = new ScriptedTelegramGateway(_ -> messages(pending(-1)));
        var future = tracker.forward(gateway, request(), vertx);
        TdApi.Message unrelated = sent(9999);
        unrelated.chatId = 201;
        tracker.onUpdate(new TdApi.UpdateMessageSendSucceeded(unrelated, -1));
        assertFalse(future.isComplete());
        tracker.onUpdate(new TdApi.UpdateMessageSendSucceeded(sent(9001), -1));
        future.onComplete(context.succeeding(result -> context.verify(() -> {
            assertEquals(9001, result.messages[0].id);
            context.completeNow();
        })));
    }

    private static TdApi.ForwardMessages request() {
        return new TdApi.ForwardMessages(200, null, 100, new long[]{10, 11}, null, true, false);
    }

    private static TdApi.Message sent(long id) {
        var message = new TdApi.Message();
        message.id = id;
        message.chatId = 200;
        return message;
    }

    private static TdApi.Message pending(long id) {
        var message = sent(id);
        message.sendingState = new TdApi.MessageSendingStatePending();
        return message;
    }

    private static TdApi.Messages messages(TdApi.Message... messages) {
        return new TdApi.Messages(messages.length, messages);
    }
}
