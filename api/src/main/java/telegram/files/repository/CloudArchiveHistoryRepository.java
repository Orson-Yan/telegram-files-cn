package telegram.files.repository;

import io.vertx.core.Future;

import java.util.List;

public interface CloudArchiveHistoryRepository {

    Future<CloudArchiveHistoryJob> create(long telegramId,
                                          long sourceChatId,
                                          long sourceTopicId,
                                          long targetChatId,
                                          long targetTopicId,
                                          String ruleJson,
                                          String scanMode,
                                          int maxMessages);

    Future<List<CloudArchiveHistoryJob>> listRecent(int limit);

    Future<List<CloudArchiveHistoryJob>> listRunnable(int limit);

    Future<Boolean> start(String id);

    Future<CloudArchiveHistoryJob> findActive(long telegramId, long sourceChatId);

    Future<Void> initializeTopics(String id, String topicIdsJson, int topicCount, long currentTopicId);

    Future<Void> advance(String id,
                         long fromMessageId,
                         int scanned,
                         int matched,
                         int queued);

    Future<Void> completeTopic(String id,
                               int nextTopicIndex,
                               long nextTopicId,
                               boolean scanCompleted,
                               String completionReason);

    Future<Void> completeDraining(String id);

    Future<Void> fail(String id, String message);

    Future<Boolean> transition(String id, String action);
}
