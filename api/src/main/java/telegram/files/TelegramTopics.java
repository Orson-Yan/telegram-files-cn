package telegram.files;

import io.vertx.core.Future;
import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** TDLib forum-topic pagination shared by the API and cloud archive worker. */
final class TelegramTopics {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_TOPICS = 5_000;

    private TelegramTopics() {
    }

    static Future<List<TdApi.ForumTopic>> listAll(TelegramGateway gateway, long chatId, String query) {
        List<TdApi.ForumTopic> topics = new ArrayList<>();
        return load(gateway, chatId, query == null ? "" : query, 0, 0, 0, topics)
                .map(ignored -> topics.stream()
                        .filter(topic -> topic != null && topic.info != null)
                        .sorted(Comparator
                                .comparing((TdApi.ForumTopic topic) -> !topic.info.isGeneral)
                                .thenComparingInt(topic -> topic.info.creationDate)
                                .thenComparingInt(topic -> topic.info.forumTopicId))
                        .toList());
    }

    private static Future<Void> load(TelegramGateway gateway,
                                     long chatId,
                                     String query,
                                     int offsetDate,
                                     long offsetMessageId,
                                     int offsetTopicId,
                                     List<TdApi.ForumTopic> topics) {
        return gateway.execute(new TdApi.GetForumTopics(
                        chatId, query, offsetDate, offsetMessageId, offsetTopicId, PAGE_SIZE))
                .compose(page -> {
                    if (page == null || page.topics == null || page.topics.length == 0) {
                        return Future.succeededFuture();
                    }
                    for (TdApi.ForumTopic topic : page.topics) {
                        if (topic != null && topics.size() < MAX_TOPICS) {
                            topics.add(topic);
                        }
                    }
                    boolean finished = topics.size() >= MAX_TOPICS
                                       || page.nextOffsetForumTopicId == 0
                                       || (page.nextOffsetDate == offsetDate
                                           && page.nextOffsetMessageId == offsetMessageId
                                           && page.nextOffsetForumTopicId == offsetTopicId);
                    if (finished) {
                        return Future.succeededFuture();
                    }
                    return load(gateway, chatId, query, page.nextOffsetDate,
                            page.nextOffsetMessageId, page.nextOffsetForumTopicId, topics);
                });
    }
}
