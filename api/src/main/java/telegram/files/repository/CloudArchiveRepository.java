package telegram.files.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface CloudArchiveRepository {

    Future<Boolean> enqueue(long telegramId,
                            long sourceChatId,
                            long sourceMessageId,
                            long sourceAlbumId,
                            long targetChatId,
                            String fileUniqueId,
                            String mode);

    Future<Boolean> claim(String id);

    Future<Void> complete(String id, long targetMessageId);

    Future<Void> skip(String id, String errorCode, String message);

    Future<Void> fail(String id, boolean retryable, long nextAttemptAt, String errorCode, String message);

    Future<Void> recoverSendingAsUnknown();

    Future<List<CloudArchiveRecord>> listDue(long now, int limit);

    Future<List<CloudArchiveRecord>> listRecent(int limit);

    Future<JsonObject> statistics();

    Future<Boolean> retry(String id);
}
