package telegram.files;

import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.TelegramRecord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CloudArchiveServiceTest {
    @Test
    void partialFailurePreservesConfirmedTargetsInsteadOfRetryingTheWholeBatch(VertxTestContext context) {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(request -> switch (request) {
            case TdApi.GetMessages value -> messages(value.messageIds);
            case TdApi.GetMessageProperties _ -> copyableProperties();
            case TdApi.ForwardMessages _ -> {
                TdApi.Messages result = messages(new long[]{9001, -2});
                var state = new TdApi.MessageSendingStateFailed();
                state.error = new TdApi.Error(429, "FLOOD_WAIT_5");
                result.messages[1].sendingState = state;
                yield result;
            }
            default -> new TdApi.Ok();
        });
        CloudArchiveService.archive(telegram(gateway), 100, List.of(10L, 11L),
                        rule(200, SettingAutoRecords.ArchiveMode.COPY))
                .onComplete(context.failing(failure -> context.verify(() -> {
                    var partial = assertInstanceOf(CloudArchiveService.PartialDelivery.class, failure);
                    assertEquals(9001L, partial.targets.get(10L));
                    assertFalse(partial.targets.containsKey(11L));
                    assertTrue(CloudArchiveService.isRetryable(partial.failures.get(11L)));
                    context.completeNow();
                })));
    }


    @Test
    void copyArchivesMessagesInOrderWithoutDownloadingMedia(VertxTestContext context) {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(request -> switch (request) {
            case TdApi.GetMessages value -> messages(value.messageIds);
            case TdApi.GetMessageProperties _ -> copyableProperties();
            case TdApi.ForwardMessages value -> messages(new long[]{9001, 9002});
            default -> new TdApi.Ok();
        });
        TelegramVerticle telegram = telegram(gateway);
        SettingAutoRecords.ArchiveRule rule = rule(200, SettingAutoRecords.ArchiveMode.COPY);

        CloudArchiveService.archive(telegram, 100, List.of(12L, 11L), rule)
                .onComplete(context.succeeding(targets -> context.verify(() -> {
                    assertEquals(9001L, targets.get(11L));
                    assertEquals(9002L, targets.get(12L));
                    assertTrue(gateway.requests().stream().noneMatch(TdApi.DownloadFile.class::isInstance));
                    TdApi.ForwardMessages forwarded = gateway.requests().stream()
                            .filter(TdApi.ForwardMessages.class::isInstance)
                            .map(TdApi.ForwardMessages.class::cast)
                            .findFirst()
                            .orElseThrow();
                    assertArrayEquals(new long[]{11, 12}, forwarded.messageIds);
                    assertTrue(forwarded.sendCopy);
                    assertFalse(forwarded.removeCaption);
                    assertTrue(forwarded.options.disableNotification);
                    context.completeNow();
                })));
    }

    @Test
    void rejectsForwardWhenTelegramDisallowsIt(VertxTestContext context) {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(request -> switch (request) {
            case TdApi.GetMessages value -> messages(value.messageIds);
            case TdApi.GetMessageProperties _ -> copyableProperties();
            default -> new TdApi.Ok();
        });
        TelegramVerticle telegram = telegram(gateway);
        SettingAutoRecords.ArchiveRule rule = rule(200, SettingAutoRecords.ArchiveMode.FORWARD);

        CloudArchiveService.archive(telegram, 100, List.of(11L), rule)
                .onComplete(context.failing(failure -> context.verify(() -> {
                    CloudArchiveService.Rejected rejected = assertInstanceOf(
                            CloudArchiveService.Rejected.class, failure
                    );
                    assertEquals("FORWARD_RESTRICTED", rejected.code());
                    assertTrue(gateway.requests().stream().noneMatch(TdApi.ForwardMessages.class::isInstance));
                    context.completeNow();
                })));
    }

    @Test
    void allowsCopyBetweenDifferentTopicsInTheSameForum(VertxTestContext context) {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(request -> switch (request) {
            case TdApi.GetMessages value -> messages(value.messageIds);
            case TdApi.GetMessageProperties _ -> copyableProperties();
            case TdApi.ForwardMessages _ -> messages(new long[]{9001});
            default -> new TdApi.Ok();
        });
        SettingAutoRecords.ArchiveRule rule = rule(100, SettingAutoRecords.ArchiveMode.COPY);
        rule.sourceTopicId = 11;
        rule.targetTopicId = 12;

        CloudArchiveService.archive(telegram(gateway), 100, List.of(11L), rule)
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    TdApi.ForwardMessages forwarded = gateway.requests().stream()
                            .filter(TdApi.ForwardMessages.class::isInstance)
                            .map(TdApi.ForwardMessages.class::cast)
                            .findFirst()
                            .orElseThrow();
                    TdApi.MessageTopicForum target = assertInstanceOf(
                            TdApi.MessageTopicForum.class, forwarded.topicId);
                    assertEquals(12, target.forumTopicId);
                    context.completeNow();
                })));
    }

    @Test
    void rejectsPreserveModeWithoutResolvedDestinationTopic(VertxTestContext context) {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(_ -> new TdApi.Ok());
        SettingAutoRecords.ArchiveRule rule = rule(200, SettingAutoRecords.ArchiveMode.COPY);
        rule.sourceTopicId = 11;
        rule.topicMode = SettingAutoRecords.ArchiveTopicMode.PRESERVE;

        CloudArchiveService.archive(telegram(gateway), 100, List.of(11L), rule)
                .onComplete(context.failing(failure -> context.verify(() -> {
                    CloudArchiveService.Rejected rejected = assertInstanceOf(
                            CloudArchiveService.Rejected.class, failure);
                    assertEquals("TOPIC_TARGET_REQUIRED", rejected.code());
                    assertTrue(gateway.requests().isEmpty());
                    context.completeNow();
                })));
    }

    @Test
    void extractsTelegramFloodWaitWithSafetyMargin() {
        TdApi.Error error = new TdApi.Error();
        error.code = 429;
        error.message = "FLOOD_WAIT_45";
        TelegramRunException failure = new TelegramRunException(error);

        assertTrue(CloudArchiveService.isRetryable(failure));
        assertEquals(47_000L, CloudArchiveService.retryAfterMillis(failure));
    }

    @Test
    void recognizesHumanReadableTelegramTopicErrors() {
        TdApi.Error error = new TdApi.Error();
        error.code = 400;
        error.message = "Message thread not found";

        assertTrue(CloudArchiveService.isTopicFailure(new TelegramRunException(error)));
    }

    @Test
    void deletionDuringSourceValidationPreventsSubmission(VertxTestContext context) {
        var gateway = new ScriptedTelegramGateway(request -> {
            if (request instanceof TdApi.GetMessages messages) return messages(messages.messageIds);
            if (request instanceof TdApi.GetMessageProperties) return copyableProperties();
            throw new AssertionError("A deleted rule submitted a Telegram send");
        });
        CloudArchiveService.archive(telegram(gateway), 100, List.of(11L),
                        rule(200, SettingAutoRecords.ArchiveMode.COPY), () -> false)
                .onComplete(context.failing(failure -> context.verify(() -> {
                    assertEquals("RULE_DELETED", assertInstanceOf(CloudArchiveService.Rejected.class, failure).code());
                    assertTrue(gateway.requests().stream().noneMatch(request -> request instanceof TdApi.ForwardMessages));
                    context.completeNow();
                })));
    }

    private static TelegramVerticle telegram(ScriptedTelegramGateway gateway) {
        TelegramVerticle telegram = new TelegramVerticle("/tmp/archive-fixture", () -> gateway);
        telegram.telegramRecord = new TelegramRecord(42, "test", "/tmp/archive-fixture", null);
        telegram.client = gateway;
        return telegram;
    }

    private static TdApi.Messages messages(long[] ids) {
        TdApi.Message[] messages = new TdApi.Message[ids.length];
        for (int index = 0; index < ids.length; index++) {
            messages[index] = new TdApi.Message();
            messages[index].id = ids[index];
            messages[index].chatId = 100;
        }
        return new TdApi.Messages(messages.length, messages);
    }

    private static TdApi.MessageProperties copyableProperties() {
        TdApi.MessageProperties properties = new TdApi.MessageProperties();
        properties.canBeCopied = true;
        properties.canBeForwarded = false;
        return properties;
    }

    private static SettingAutoRecords.ArchiveRule rule(long targetChatId,
                                                        SettingAutoRecords.ArchiveMode mode) {
        SettingAutoRecords.ArchiveRule rule = new SettingAutoRecords.ArchiveRule();
        rule.targetChatId = targetChatId;
        rule.mode = mode;
        rule.preserveCaption = true;
        rule.disableNotification = true;
        return rule;
    }
}
