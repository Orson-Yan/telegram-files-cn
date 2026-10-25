package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.impl.ArchiveOperationsRepository;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Read-only recovery probes resume known failures; uncertain sends remain under reconciliation. */
public final class ArchiveAutoRecovery {
    private final Supplier<TelegramHealthMonitor> health;
    private boolean busy;

    public ArchiveAutoRecovery(Supplier<TelegramHealthMonitor> health) { this.health = health; }
    private ArchiveOperationsRepository store() { return DataVerticle.archiveOperations; }

    Future<Void> scan() {
        if (busy || store() == null) return Future.succeededFuture();
        var rules = AutomationsHolder.INSTANCE.autoRecords().getArchiveEnabledItems();
        if (rules.isEmpty()) return Future.succeededFuture();
        busy = true;
        Tuple values = Tuple.of(System.currentTimeMillis());
        String scope = String.join(" OR ", rules.stream().map(rule -> {
            values.addLong(rule.telegramId).addLong(rule.chatId).addLong(rule.archive.rule.targetChatId);
            return "(r.telegram_id = ? AND r.source_chat_id = ? AND r.target_chat_id = ?)";
        }).toList());
        return store().query("""
                SELECT r.*, s.signature AS gate_signature, s.rounds, s.next_check_at
                FROM telegram_archive_record r LEFT JOIN archive_recovery_state s ON s.record_id = r.id
                WHERE r.status = 'FAILED' AND (s.next_check_at IS NULL OR s.next_check_at <= ?)
                  AND (r.last_error_code IN ('MAX_RETRIES_EXCEEDED', 'TELEGRAM_400', 'TELEGRAM_401', 'TELEGRAM_403', 'TELEGRAM_404', 'TELEGRAM_420', 'TELEGRAM_429', 'TELEGRAM_WAIT', 'TIMEOUT', 'DELIVERY_FAILED')
                    OR r.last_error_code LIKE 'TELEGRAM_5%%')
                  AND (r.source_album_id = 0 OR NOT EXISTS (SELECT 1 FROM telegram_archive_record p
                    WHERE p.telegram_id = r.telegram_id AND p.source_chat_id = r.source_chat_id AND p.target_chat_id = r.target_chat_id
                      AND p.source_album_id = r.source_album_id AND p.status IN ('COMPLETED', 'UNKNOWN', 'SENDING')))
                  AND (%s) ORDER BY r.updated_at, r.source_message_id LIMIT 20
                """.formatted(scope), values).compose(rows -> {
            Future<Void> work = Future.succeededFuture();
            for (Object value : rows) work = work.compose(_ -> inspect((JsonObject) value).recover(_ -> Future.succeededFuture()));
            return work;
        }).onComplete(_ -> busy = false);
    }

    private Future<Void> inspect(JsonObject row) {
        CloudArchiveRecord record = from(row);
        TelegramVerticle telegram = TelegramVerticles.get(record.telegramId()).orElse(null);
        String oldSignature = row.getString("gate_signature");
        int rounds = row.getValue("rounds") instanceof Number count ? count.intValue() : 0;
        long now = System.currentTimeMillis();
        JsonObject state = telegram == null ? new JsonObject() : health.get().snapshot(telegram);
        String proxy = state.getString("proxy", "");
        if (telegram == null || !"HEALTHY".equals(state.getString("status"))) {
            return store().rememberRecovery(record.id(), "offline:" + proxy, rounds, now + 60_000);
        }
        return probe(telegram, record, proxy).compose(gate -> {
            if (gate.sourceUnavailable()) return store().skipUnavailableSource(record);
            if (!gate.allowed()) return store().rememberRecovery(record.id(), gate.signature(), rounds, now + 60_000);
            boolean changed = oldSignature != null && !oldSignature.equals(gate.signature());
            int nextRound = changed ? 1 : rounds + 1;
            boolean transientFailure = Set.of("MAX_RETRIES_EXCEEDED", "TELEGRAM_420", "TELEGRAM_429", "TELEGRAM_WAIT", "TIMEOUT").contains(record.lastErrorCode())
                    || record.lastErrorCode().startsWith("TELEGRAM_5");
            if (oldSignature != null && !changed && (!transientFailure || nextRound > 3)) {
                return store().rememberRecovery(record.id(), gate.signature(), rounds, now + 60_000);
            }
            return store().rememberRecovery(record.id(), gate.signature(), rounds, now + 60_000)
                    .compose(_ -> store().resumeFailed(record, gate.signature(), nextRound)).mapEmpty();
        }).recover(_ -> store().rememberRecovery(record.id(), oldSignature == null ? "probe-error:" + proxy : oldSignature, rounds, now + 60_000));
    }

    private record Gate(String signature, boolean allowed, boolean sourceUnavailable) {}

    private Future<Gate> probe(TelegramVerticle telegram, CloudArchiveRecord record, String proxy) {
        return telegram.client.execute(new TdApi.GetMessage(record.sourceChatId(), record.sourceMessageId()))
                .timeout(8, TimeUnit.SECONDS).recover(failure -> failure instanceof TelegramRunException error && error.getError().code == 404
                        ? Future.succeededFuture(new TdApi.Message()) : Future.failedFuture(failure)).compose(message -> {
                    if (message == null) return Future.succeededFuture(new Gate("source-unknown:" + proxy, false, false));
                    if (message.id == 0) return Future.succeededFuture(new Gate("source-removed:" + proxy, false, true));
                    return telegram.client.execute(new TdApi.GetMessageProperties(record.sourceChatId(), record.sourceMessageId()))
                            .timeout(8, TimeUnit.SECONDS).compose(properties -> {
                        boolean allowed = "FORWARD".equals(record.mode()) ? properties.canBeForwarded : properties.canBeCopied;
                        if (!allowed || properties.hasProtectedContentByCurrentUser || properties.hasProtectedContentByOtherUser) {
                            return Future.succeededFuture(new Gate("source-unavailable:" + proxy, false, true));
                        }
                        return telegram.client.execute(new TdApi.GetChat(record.targetChatId())).timeout(8, TimeUnit.SECONDS)
                                .compose(chat -> destinationAllowed(telegram, chat, message).map(canSend ->
                                        new Gate((canSend ? "ready:" : "denied:") + proxy, canSend, false)));
                    });
                });
    }

    private Future<Boolean> destinationAllowed(TelegramVerticle telegram, TdApi.Chat chat, TdApi.Message message) {
        if (chat == null || chat.type == null || telegram.telegramRecord == null) return Future.succeededFuture(false);
        if (chat.type instanceof TdApi.ChatTypePrivate user) {
            if (user.userId == telegram.telegramRecord.id()) return Future.succeededFuture(true);
            return telegram.client.execute(new TdApi.CanSendMessageToUser(user.userId, false)).timeout(8, TimeUnit.SECONDS)
                    .map(result -> result instanceof TdApi.CanSendMessageToUserResultOk);
        }
        if (!(chat.type instanceof TdApi.ChatTypeBasicGroup || chat.type instanceof TdApi.ChatTypeSupergroup)) return Future.succeededFuture(false);
        boolean channel = chat.type instanceof TdApi.ChatTypeSupergroup group && group.isChannel;
        return telegram.client.execute(new TdApi.GetChatMember(chat.id, new TdApi.MessageSenderUser(telegram.telegramRecord.id())))
                .timeout(8, TimeUnit.SECONDS).map(member -> {
                    if (member == null || member.status == null) return false;
                    if (member.status instanceof TdApi.ChatMemberStatusCreator creator) return creator.isMember;
                    if (member.status instanceof TdApi.ChatMemberStatusAdministrator admin) return !channel || (admin.rights != null && admin.rights.canPostMessages);
                    if (channel) return false;
                    if (member.status instanceof TdApi.ChatMemberStatusRestricted restricted) return restricted.isMember && permits(restricted.permissions, message.content);
                    return member.status instanceof TdApi.ChatMemberStatusMember && permits(chat.permissions, message.content);
                });
    }

    private static boolean permits(TdApi.ChatPermissions permissions, TdApi.MessageContent content) {
        if (permissions == null) return false;
        return switch (content) {
            case TdApi.MessagePhoto ignored -> permissions.canSendPhotos;
            case TdApi.MessageVideo ignored -> permissions.canSendVideos;
            case TdApi.MessageAudio ignored -> permissions.canSendAudios;
            case TdApi.MessageDocument ignored -> permissions.canSendDocuments;
            case TdApi.MessageVideoNote ignored -> permissions.canSendVideoNotes;
            case TdApi.MessageVoiceNote ignored -> permissions.canSendVoiceNotes;
            case TdApi.MessagePoll ignored -> permissions.canSendPolls;
            case TdApi.MessageAnimation ignored -> permissions.canSendOtherMessages;
            case TdApi.MessageSticker ignored -> permissions.canSendOtherMessages;
            default -> permissions.canSendBasicMessages;
        };
    }

    private static CloudArchiveRecord from(JsonObject row) {
        return new CloudArchiveRecord(row.getString("id"), number(row, "telegram_id"), number(row, "source_chat_id"), number(row, "source_topic_id"),
                number(row, "source_message_id"), number(row, "source_album_id"), number(row, "target_chat_id"), number(row, "target_topic_id"),
                row.getValue("target_message_id") instanceof Number target ? target.longValue() : null, row.getString("file_unique_id"), row.getString("mode"),
                row.getString("topic_mode"), row.getString("history_job_id"), number(row, "delivery_sequence"), row.getString("status"), (int) number(row, "attempt_count"),
                number(row, "next_attempt_at"), row.getString("last_error_code"), row.getString("last_error_message"), number(row, "created_at"), number(row, "updated_at"));
    }
    private static long number(JsonObject row, String key) { return row.getValue(key) instanceof Number value ? value.longValue() : 0; }
}
