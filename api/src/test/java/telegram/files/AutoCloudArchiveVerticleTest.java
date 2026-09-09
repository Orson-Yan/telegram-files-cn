package telegram.files;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.CloudArchiveHistoryJob;
import telegram.files.repository.CloudArchiveHistoryRepository;
import telegram.files.repository.SettingAutoRecords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
class AutoCloudArchiveVerticleTest {

    @Test
    void topicDiscoveryStartsScanningWithoutCreatingEveryDestinationTopic(
            VertxTestContext context) {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(request -> switch (request) {
            case TdApi.GetForumTopics _ -> new TdApi.ForumTopics(2, new TdApi.ForumTopic[]{
                    topic(11, "General", true), topic(22, "Drama", false)
            }, 0, 0, 0);
            default -> new TdApi.Ok();
        });
        TelegramVerticle telegram = mock(TelegramVerticle.class);
        telegram.client = gateway;
        when(telegram.isForum(anyLong())).thenReturn(true);

        CloudArchiveHistoryRepository repository = mock(CloudArchiveHistoryRepository.class);
        when(repository.initializeTopics("job-1", "[11,22]", 2, 11))
                .thenReturn(Future.succeededFuture());
        CloudArchiveHistoryRepository previous = DataVerticle.cloudArchiveHistoryRepository;
        DataVerticle.cloudArchiveHistoryRepository = repository;

        SettingAutoRecords.ArchiveRule rule = new SettingAutoRecords.ArchiveRule();
        rule.topicMode = SettingAutoRecords.ArchiveTopicMode.PRESERVE;
        CloudArchiveHistoryJob job = new CloudArchiveHistoryJob(
                "job-1", 7, 100, 0, 200, 0, "{}", "RUNNING",
                "ALL", "DISCOVERING", 0, null, 0, 0, 0,
                0, 0, 0, 0, null, null, 1_000, 1_000);

        new AutoCloudArchiveVerticle().initializeHistoryTopics(telegram, job, rule)
                .onComplete(result -> {
                    DataVerticle.cloudArchiveHistoryRepository = previous;
                    if (result.failed()) {
                        context.failNow(result.cause());
                        return;
                    }
                    context.verify(() -> {
                        verify(repository).initializeTopics("job-1", "[11,22]", 2, 11);
                        assertEquals(1, gateway.requests().size());
                        assertFalse(gateway.requests().stream()
                                .anyMatch(TdApi.CreateForumTopic.class::isInstance));
                        context.completeNow();
                    });
                });
    }

    private static TdApi.ForumTopic topic(int id, String name, boolean general) {
        TdApi.ForumTopicInfo info = new TdApi.ForumTopicInfo(
                100, id, name, new TdApi.ForumTopicIcon(0x6FB9F0, 0), 1_000,
                null, general, false, false, false, false);
        return new TdApi.ForumTopic(info, null, 0, false,
                0, 0, 0, 0, 0, 0, null, null);
    }
}
