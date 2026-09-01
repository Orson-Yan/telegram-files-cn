package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class TdApiHelpTest {

    @Test
    void allFilesUsesNoTelegramTypeFilter() {
        Assertions.assertNull(TdApiHelp.getSearchMessagesFilter(null));
        Assertions.assertNull(TdApiHelp.getSearchMessagesFilter(""));
        Assertions.assertNull(TdApiHelp.getSearchMessagesFilter("all"));
        Assertions.assertInstanceOf(
                TdApi.SearchMessagesFilterPhotoAndVideo.class,
                TdApiHelp.getSearchMessagesFilter("media")
        );
    }

    @Test
    void getFunctionTest() {
        TdApi.Function<?> function = TdApiHelp.getFunction("GetMe", null);
        Assertions.assertNotNull(function, "function is null");
        Assertions.assertTrue(function instanceof TdApi.GetMe, "function is not GetMe");

        TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
        searchChatMessages.chatId = 0L;
        searchChatMessages.query = "";
        searchChatMessages.fromMessageId = 0;
        searchChatMessages.offset = 0;
        searchChatMessages.limit = 10;
        searchChatMessages.filter = new TdApi.SearchMessagesFilterEmpty();

        function = TdApiHelp.getFunction("SearchChatMessages",
                Map.of(
                        "chatId", searchChatMessages.chatId,
                        "query", searchChatMessages.query,
                        "fromMessageId", searchChatMessages.fromMessageId,
                        "offset", searchChatMessages.offset,
                        "limit", searchChatMessages.limit,
                        "filter", Map.of("@type", -869395657)
                )
        );
        Assertions.assertNotNull(function, "function is null");
        Assertions.assertEquals(searchChatMessages.chatId, ((TdApi.SearchChatMessages) function).chatId, "function is not equals SearchChatMessages");
        Assertions.assertInstanceOf(TdApi.SearchMessagesFilterEmpty.class, ((TdApi.SearchChatMessages) function).filter, "function is not equals SearchChatMessages");
    }

}
