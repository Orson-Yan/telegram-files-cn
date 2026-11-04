package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramUpdateHandlerTest {

    @Test
    void dispatchesUpdateFileWithoutFallthrough() {
        TelegramUpdateHandler handler = new TelegramUpdateHandler();
        AtomicBoolean fileUpdatedCalled = new AtomicBoolean(false);
        handler.setOnFileUpdated(_ -> fileUpdatedCalled.set(true));

        TdApi.File file = new TdApi.File();
        file.id = 123;
        handler.onResult(new TdApi.UpdateFile(file));

        assertTrue(fileUpdatedCalled.get());
    }

    @Test
    void dispatchesUpdateNewMessageWithoutFallingThroughToChatUpdated() {
        TelegramUpdateHandler handler = new TelegramUpdateHandler();
        AtomicBoolean messageReceived = new AtomicBoolean(false);
        AtomicBoolean chatUpdated = new AtomicBoolean(false);

        handler.setOnMessageReceived(_ -> messageReceived.set(true));
        handler.setOnChatUpdated(_ -> chatUpdated.set(true));

        TdApi.Message message = new TdApi.Message();
        message.id = 456;
        handler.onResult(new TdApi.UpdateNewMessage(message));

        assertTrue(messageReceived.get());
        assertFalse(chatUpdated.get(), "UpdateNewMessage must not fall through to onChatUpdated");
    }

    @Test
    void dispatchesSupergroupUpdateToChatUpdated() {
        TelegramUpdateHandler handler = new TelegramUpdateHandler();
        AtomicInteger chatUpdatedCount = new AtomicInteger(0);

        handler.setOnChatUpdated(_ -> chatUpdatedCount.incrementAndGet());

        TdApi.Supergroup supergroup = new TdApi.Supergroup();
        supergroup.id = 789;
        handler.onResult(new TdApi.UpdateSupergroup(supergroup));

        assertEquals(1, chatUpdatedCount.get());
    }
}
