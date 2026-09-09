package telegram.files;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.CloudArchiveTopicMap;
import telegram.files.repository.CloudArchiveTopicRepository;
import telegram.files.repository.TelegramRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@ExtendWith(VertxExtension.class)
class CloudArchiveTopicServiceTest {

    @Test
    void recreatesAndPersistsADeletedDestinationTopic(VertxTestContext context) {
        InMemoryTopicRepository repository = new InMemoryTopicRepository(new CloudArchiveTopicMap(
                42, 100, 11, "Source topic", 200, 21, "Deleted topic",
                false, 1_000, 1_000));
        CloudArchiveTopicRepository previous = DataVerticle.cloudArchiveTopicRepository;
        DataVerticle.cloudArchiveTopicRepository = repository;
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(request -> switch (request) {
            case TdApi.GetForumTopic value when value.chatId == 200 -> topicMissing();
            case TdApi.GetForumTopic _ -> topic(100, 11, "Source topic");
            case TdApi.GetForumTopics _ -> new TdApi.ForumTopics(
                    0, new TdApi.ForumTopic[0], 0, 0, 0);
            case TdApi.CreateForumTopic _ -> topicInfo(200, 31, "Source topic");
            default -> new TdApi.Ok();
        });
        TelegramVerticle telegram = new TelegramVerticle("/tmp/topic-fixture", () -> gateway);
        telegram.telegramRecord = new TelegramRecord(42, "test", "/tmp/topic-fixture", null);
        telegram.client = gateway;
        CloudArchiveTopicService.invalidate(42, 100, 11, 200);

        CloudArchiveTopicService.resolve(telegram, 100, 11, 200).onComplete(result -> {
            DataVerticle.cloudArchiveTopicRepository = previous;
            CloudArchiveTopicService.invalidate(42, 100, 11, 200);
            if (result.failed()) {
                context.failNow(result.cause());
                return;
            }
            context.verify(() -> {
                assertEquals(31L, result.result());
                assertEquals(31L, repository.mapping.targetTopicId());
                assertInstanceOf(TdApi.CreateForumTopic.class,
                        gateway.requests().stream()
                                .filter(TdApi.CreateForumTopic.class::isInstance)
                                .findFirst()
                                .orElseThrow());
                context.completeNow();
            });
        });
    }

    private static TdApi.Error topicMissing() {
        TdApi.Error error = new TdApi.Error();
        error.code = 400;
        error.message = "TOPIC_NOT_FOUND";
        return error;
    }

    private static TdApi.ForumTopic topic(long chatId, int topicId, String name) {
        return new TdApi.ForumTopic(topicInfo(chatId, topicId, name), null, 0,
                false, 0, 0, 0, 0, 0, 0, null, null);
    }

    private static TdApi.ForumTopicInfo topicInfo(long chatId, int topicId, String name) {
        return new TdApi.ForumTopicInfo(
                chatId, topicId, name, new TdApi.ForumTopicIcon(0x6FB9F0, 0),
                1_000, null, false, false, false, false, false);
    }

    private static final class InMemoryTopicRepository implements CloudArchiveTopicRepository {
        private CloudArchiveTopicMap mapping;

        private InMemoryTopicRepository(CloudArchiveTopicMap mapping) {
            this.mapping = mapping;
        }

        @Override
        public Future<CloudArchiveTopicMap> find(long telegramId,
                                                  long sourceChatId,
                                                  long sourceTopicId,
                                                  long targetChatId) {
            return Future.succeededFuture(mapping);
        }

        @Override
        public Future<CloudArchiveTopicMap> save(long telegramId,
                                                  long sourceChatId,
                                                  long sourceTopicId,
                                                  String sourceTopicName,
                                                  long targetChatId,
                                                  long targetTopicId,
                                                  String targetTopicName,
                                                  boolean general) {
            mapping = new CloudArchiveTopicMap(
                    telegramId, sourceChatId, sourceTopicId, sourceTopicName,
                    targetChatId, targetTopicId, targetTopicName, general,
                    mapping.createdAt(), 2_000);
            return Future.succeededFuture(mapping);
        }
    }
}
