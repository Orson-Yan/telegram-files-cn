package telegram.files.repository;

import io.vertx.core.Future;

public interface CloudArchiveTopicRepository {
    Future<CloudArchiveTopicMap> find(long telegramId,
                                      long sourceChatId,
                                      long sourceTopicId,
                                      long targetChatId);

    default Future<Boolean> targetMappedToAnotherSource(long telegramId,
                                                         long sourceChatId,
                                                         long sourceTopicId,
                                                         long targetChatId,
                                                         long targetTopicId) {
        return Future.succeededFuture(false);
    }

    Future<CloudArchiveTopicMap> save(long telegramId,
                                      long sourceChatId,
                                      long sourceTopicId,
                                      String sourceTopicName,
                                      long targetChatId,
                                      long targetTopicId,
                                      String targetTopicName,
                                      boolean general);
}
