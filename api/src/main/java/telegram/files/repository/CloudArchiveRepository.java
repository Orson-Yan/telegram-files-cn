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
                targetChatId, 0, fileUniqueId, mode);
    }

    Future<Boolean> enqueue(long telegramId,
                            long sourceChatId,
                            long sourceTopicId,
                            long sourceMessageId,
                            long sourceAlbumId,
                            long targetChatId,
                            long targetTopicId,
                            String fileUniqueId,
                            String mode);

    Future<Boolean> stage(long telegramId,
                          long sourceChatId,
                          long sourceTopicId,
                          long sourceMessageId,
                          long sourceAlbumId,
                          long targetChatId,
                          long targetTopicId,
                          String fileUniqueId,
                          String mode,
                          String historyJobId);

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

    Future<Long> countPending(long telegramId);

    Future<Long> cooldownUntil(long telegramId, long now);

    Future<Void> defer(String id, long nextAttemptAt, String code, String message);

    Future<JsonObject> statistics();

    Future<Boolean> retry(String id);
}
