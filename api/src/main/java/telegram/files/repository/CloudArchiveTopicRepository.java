package telegram.files.repository;

import io.vertx.core.Future;

public interface CloudArchiveTopicRepository {
    Future<CloudArchiveTopicMap> find(long telegramId,
                                      long sourceChatId,
                                      long sourceTopicId,
                                      long targetChatId);

    Future<CloudArchiveTopicMap> save(long telegramId,
                                      long sourceChatId,
                                      long sourceTopicId,
                                      String sourceTopicName,
                                      long targetChatId,
                                      long targetTopicId,
                                      String targetTopicName,
                                      boolean general);
}
