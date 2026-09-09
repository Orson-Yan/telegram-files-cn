package telegram.files.repository;

import io.vertx.core.Future;

import java.util.List;

public interface CloudArchiveSyncRepository {

    Future<CloudArchiveSyncState> find(long telegramId, long sourceChatId,
                                       long sourceTopicId, long targetChatId);

    Future<List<CloudArchiveSyncState>> listRoute(long telegramId, long sourceChatId,
                                                  long targetChatId);

    Future<CloudArchiveSyncState> ensure(long telegramId, long sourceChatId,
                                         long sourceTopicId, long targetChatId,
                                         long baselineMessageId);

    Future<Void> beginRecovery(CloudArchiveSyncState state, long targetMessageId);

    Future<Void> advanceRecovery(CloudArchiveSyncState state, long cursorMessageId,
                                 int scanned, int matched, int queued);

    Future<Void> completeRecovery(CloudArchiveSyncState state);

    Future<Void> touch(CloudArchiveSyncState state);

    Future<Void> fail(CloudArchiveSyncState state, String message);
}
