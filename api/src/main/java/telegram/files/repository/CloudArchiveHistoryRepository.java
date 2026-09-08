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
                                          int maxMessages);

    Future<List<CloudArchiveHistoryJob>> listRecent(int limit);

    Future<List<CloudArchiveHistoryJob>> listRunnable(int limit);

    Future<Boolean> start(String id);

    Future<Void> advance(String id,
                         long fromMessageId,
                         int scanned,
                         int matched,
                         int queued,
                         boolean completed);

    Future<Void> fail(String id, String message);

    Future<Boolean> transition(String id, String action);
}
