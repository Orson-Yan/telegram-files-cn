package telegram.files;

import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class TelegramTopicsTest {

    @Test
    void loadsEveryPageAndSortsGeneralFirst(VertxTestContext context) {
        AtomicInteger page = new AtomicInteger();
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(request -> {
            TdApi.GetForumTopics query = (TdApi.GetForumTopics) request;
            if (page.getAndIncrement() == 0) {
                return new TdApi.ForumTopics(3, new TdApi.ForumTopic[]{
                        topic(22, "Later", 20, false),
                        topic(1, "General", 1, true)
                }, 20, 200, 22);
            }
            assertEquals(20, query.offsetDate);
            assertEquals(200, query.offsetMessageId);
            assertEquals(22, query.offsetForumTopicId);
            return new TdApi.ForumTopics(3, new TdApi.ForumTopic[]{
                    topic(11, "Earlier", 10, false)
            }, 0, 0, 0);
        });

        TelegramTopics.listAll(gateway, 100, "")
                .onComplete(context.succeeding(topics -> context.verify(() -> {
                    assertEquals(2, page.get());
                    assertEquals(3, topics.size());
                    assertEquals(1, topics.get(0).info.forumTopicId);
                    assertEquals(11, topics.get(1).info.forumTopicId);
                    assertEquals(22, topics.get(2).info.forumTopicId);
                    context.completeNow();
                })));
    }

    private static TdApi.ForumTopic topic(int id, String name, int createdAt, boolean general) {
        TdApi.ForumTopicInfo info = new TdApi.ForumTopicInfo(
                100, id, name, new TdApi.ForumTopicIcon(0x6FB9F0, 0), createdAt,
                null, general, false, false, false, false);
        return new TdApi.ForumTopic(info, null, 0, false,
                0, 0, 0, 0, 0, 0, null, null);
    }
}
