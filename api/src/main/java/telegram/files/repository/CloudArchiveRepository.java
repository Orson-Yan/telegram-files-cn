package telegram.files.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface CloudArchiveRepository {

    default Future<Boolean> enqueue(long telegramId,
                                    long sourceChatId,
                                    long sourceMessageId,
                                    long sourceAlbumId,
                                    long targetChatId,
                                    String fileUniqueId,
                                    String mode) {
        return enqueue(telegramId, sourceChatId, 0, sourceMessageId, sourceAlbumId,
                targetChatId, 0, fileUniqueId, mode, "MERGE");
    }

    default Future<Boolean> enqueue(long telegramId,
                                    long sourceChatId,
                                    long sourceTopicId,
                                    long sourceMessageId,
                                    long sourceAlbumId,
                                    long targetChatId,
                                    long targetTopicId,
                                    String fileUniqueId,
                                    String mode) {
        return enqueue(telegramId, sourceChatId, sourceTopicId, sourceMessageId,
                sourceAlbumId, targetChatId, targetTopicId, fileUniqueId, mode, "MERGE");
    }

    Future<Boolean> enqueue(long telegramId,
                            long sourceChatId,
                            long sourceTopicId,
                            long sourceMessageId,
                            long sourceAlbumId,
                            long targetChatId,
                            long targetTopicId,
                            String fileUniqueId,
                            String mode,
                            String topicMode);

    default Future<Boolean> stage(long telegramId,
                                  long sourceChatId,
                                  long sourceTopicId,
                                  long sourceMessageId,
                                  long sourceAlbumId,
                                  long targetChatId,
                                  long targetTopicId,
                                  String fileUniqueId,
                                  String mode,
                                  String historyJobId) {
        return stage(telegramId, sourceChatId, sourceTopicId, sourceMessageId,
                sourceAlbumId, targetChatId, targetTopicId, fileUniqueId, mode,
                "MERGE", historyJobId);
    }

    Future<Boolean> stage(long telegramId,
                          long sourceChatId,
                          long sourceTopicId,
                          long sourceMessageId,
                          long sourceAlbumId,
                          long targetChatId,
                          long targetTopicId,
                          String fileUniqueId,
                          String mode,
                          String topicMode,
                          String historyJobId);

    Future<Void> updateTopics(String id, long sourceTopicId, long targetTopicId, String topicMode);

    Future<Void> releaseHistory(String historyJobId);

    Future<Long> countOutstandingHistory(String historyJobId);

    Future<Void> cancelHistory(String historyJobId);

    Future<Boolean> claim(String id);

    Future<Void> complete(String id, long targetMessageId);

    Future<Void> skip(String id, String errorCode, String message);

    Future<Void> fail(String id, boolean retryable, long nextAttemptAt, String errorCode, String message);

    Future<Void> recoverSendingAsUnknown();

    Future<List<CloudArchiveRecord>> listDue(long now, int limit);

    Future<List<CloudArchiveRecord>> listRecent(int limit);

    Future<Long> countCompletedSince(long telegramId, long since);

    Future<Long> cooldownUntil(long telegramId, long now);

    Future<Void> defer(String id, long nextAttemptAt, String code, String message);

    Future<JsonObject> statistics();

    Future<Boolean> retry(String id);
}
