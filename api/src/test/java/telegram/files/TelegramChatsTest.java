package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramChatsTest {

    @Test
    void identifiesForumGroupsButNotChannels() {
        TelegramChats chats = new TelegramChats(new ScriptedTelegramGateway(_ -> new TdApi.Ok()));
        TdApi.Supergroup supergroup = new TdApi.Supergroup();
        supergroup.id = 10;
        supergroup.isForum = true;
        chats.onChatUpdated(new TdApi.UpdateSupergroup(supergroup));

        TdApi.Chat forum = new TdApi.Chat();
        forum.id = 100;
        forum.type = new TdApi.ChatTypeSupergroup(10, false);
        forum.positions = new TdApi.ChatPosition[0];
        chats.onChatUpdated(new TdApi.UpdateNewChat(forum));
        assertTrue(chats.isForum(100));

        TdApi.Chat channel = new TdApi.Chat();
        channel.id = 200;
        channel.type = new TdApi.ChatTypeSupergroup(10, true);
        channel.positions = new TdApi.ChatPosition[0];
        chats.onChatUpdated(new TdApi.UpdateNewChat(channel));
        assertFalse(chats.isForum(200));
    }
}
