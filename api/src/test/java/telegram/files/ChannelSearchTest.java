package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChannelSearchTest {
    @Test void searchesPastFirstHundredAndIncludesArchivedChannels() {
        Map<Long,TdApi.Chat> known=new HashMap<>();
        TelegramChats chats=new TelegramChats(new ScriptedTelegramGateway(request -> {
            if(request instanceof TdApi.LoadChats) return new TdApi.Error(404,"All loaded");
            if(request instanceof TdApi.SearchChatsOnServer) {
                TdApi.Chats result=new TdApi.Chats();result.chatIds=new long[0];return result;
            }
            if(request instanceof TdApi.GetChat get) return known.get(get.chatId);
            return new TdApi.Ok();
        }));
        for(int i=1;i<=150;i++) {
            TdApi.Chat chat=new TdApi.Chat();chat.id=i;chat.title="Channel "+i;
            TdApi.ChatPosition position=new TdApi.ChatPosition();position.list=new TdApi.ChatListMain();position.order=1000-i;
            chat.positions=new TdApi.ChatPosition[]{position};known.put(chat.id,chat);
            chats.onChatUpdated(new TdApi.UpdateNewChat(chat));
        }
        TdApi.Chat archived=new TdApi.Chat();archived.id=999;archived.title="Archived target";
        TdApi.ChatPosition archivePosition=new TdApi.ChatPosition();archivePosition.list=new TdApi.ChatListArchive();archivePosition.order=500;
        archived.positions=new TdApi.ChatPosition[]{archivePosition};known.put(999L,archived);
        chats.onChatUpdated(new TdApi.UpdateNewChat(archived));
        var beyond=chats.search(null,"CHANNEL 150","all",100,0,false).toCompletionStage().toCompletableFuture().join();
        assertEquals(1,beyond.size());assertEquals(150,beyond.getFirst().id);
        var foundArchive=chats.search(null,"archived","all",100,0,false).toCompletionStage().toCompletableFuture().join();
        assertEquals(999,foundArchive.getFirst().id);
        var page=chats.search(null,"","main",100,100,false).toCompletionStage().toCompletableFuture().join();
        assertEquals(50,page.size());
    }
    @Test void failedLoadingCanBeRetriedInsteadOfBeingMarkedComplete() {
        AtomicInteger calls=new AtomicInteger();
        TelegramChats chats=new TelegramChats(new ScriptedTelegramGateway(_ -> calls.incrementAndGet()==1
            ?new TdApi.Error(500,"network"):new TdApi.Error(404,"All loaded")));
        assertThrows(java.util.concurrent.CompletionException.class,() -> chats.ensureLoaded(false).toCompletionStage().toCompletableFuture().join());
        assertDoesNotThrow(() -> chats.ensureLoaded(false).toCompletionStage().toCompletableFuture().join());
        assertEquals(3,calls.get());
    }
}
