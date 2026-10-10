package telegram.files;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.CloudArchiveHistoryJob;
import telegram.files.repository.CloudArchiveHistoryRepository;
import telegram.files.repository.CloudArchiveRecord;
import telegram.files.repository.CloudArchiveRepository;
import telegram.files.repository.SettingAutoRecords;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CloudArchiveExecutionOrderTest {
    private CloudArchiveRepository previousRepository;
    private CloudArchiveHistoryRepository previousHistories;
    private List<SettingAutoRecords.Automation> previousRules;
    private CloudArchiveRepository repository;
    private CloudArchiveHistoryRepository histories;
    private Map<Long, Long> previousCooldowns;

    @BeforeEach
    void setup() {
        previousRepository = DataVerticle.cloudArchiveRepository;
        previousHistories = DataVerticle.cloudArchiveHistoryRepository;
        previousRules = AutomationsHolder.INSTANCE.autoRecords().automations;
        previousCooldowns = new HashMap<>(AutoCloudArchiveVerticle.ACCOUNT_COOLDOWN_UNTIL);
        AutoCloudArchiveVerticle.ACCOUNT_COOLDOWN_UNTIL.clear();
        repository = mock(CloudArchiveRepository.class);
        histories = mock(CloudArchiveHistoryRepository.class);
        DataVerticle.cloudArchiveRepository = repository;
        DataVerticle.cloudArchiveHistoryRepository = histories;
        var rule = new SettingAutoRecords.Automation();
        rule.telegramId = 7;
        rule.chatId = 100;
        rule.archive.enabled = true;
        rule.archive.rule.targetChatId = 200;
        rule.archive.rule.strictOrder = true;
        AutomationsHolder.INSTANCE.autoRecords().automations = new ArrayList<>(List.of(rule));
        when(histories.findActive(7, 100)).thenReturn(Future.succeededFuture(null));
    }

    @AfterEach
    void restore() {
        DataVerticle.cloudArchiveRepository = previousRepository;
        DataVerticle.cloudArchiveHistoryRepository = previousHistories;
        AutomationsHolder.INSTANCE.autoRecords().automations = previousRules;
        AutoCloudArchiveVerticle.ACCOUNT_COOLDOWN_UNTIL.clear();
        AutoCloudArchiveVerticle.ACCOUNT_COOLDOWN_UNTIL.putAll(previousCooldowns);
    }

    @Test
    void prefetchedTailIsDiscardedBeforeClaimWhenPredecessorBecameBlocked(VertxTestContext context) {
        when(repository.isBatchReady(anyList(), anyLong(), eq(true))).thenReturn(Future.succeededFuture(false));
        var tail = List.of(record(20, 0));
        new AutoCloudArchiveVerticle().process(new AutoCloudArchiveVerticle.ArchiveWork(tail))
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    verify(repository).isBatchReady(eq(tail), anyLong(), eq(true));
                    verify(repository, never()).claim(anyString());
                    verify(repository, never()).cooldownUntil(anyLong(), anyLong());
                    context.completeNow();
                })));
    }

    @Test
    void startingStrictHistoryHoldsAlreadyPrefetchedLiveWork(VertxTestContext context) {
        var job = new CloudArchiveHistoryJob("job-1", 7, 100, 0, 200, 0, "{}", "RUNNING",
                "ALL", "SCANNING", 0, 0, "2026-10-10", 0, null, 0, 0, 0,
                0, 0, 0, 0, null, null, 1_000, 1_000);
        when(histories.findActive(7, 100)).thenReturn(Future.succeededFuture(job));
        new AutoCloudArchiveVerticle().process(new AutoCloudArchiveVerticle.ArchiveWork(List.of(record(20, 0))))
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    verifyNoInteractions(repository);
                    context.completeNow();
                })));
    }

    @Test
    void unconfirmedPartialDeliveryPersistsSuccessAndDoesNotAutomaticallyRetryPendingMember(
            VertxTestContext context) {
        when(repository.complete("10", 9001)).thenReturn(Future.succeededFuture());
        when(repository.unknown(eq("11"), eq("DELIVERY_UNCONFIRMED"), anyString()))
                .thenReturn(Future.succeededFuture());
        var failure = new CloudArchiveService.PartialDelivery(Map.of(10L, 9001L),
                Map.of(11L, new CloudArchiveDeliveryTracker.Unconfirmed("confirmation timed out")));
        new AutoCloudArchiveVerticle().handleFailure(List.of(record(10, 0), record(11, 0)), failure)
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    verify(repository).complete("10", 9001);
                    verify(repository).unknown(eq("11"), eq("DELIVERY_UNCONFIRMED"), anyString());
                    verify(repository, never()).fail(anyString(), anyBoolean(), anyLong(), anyString(), anyString());
                    context.completeNow();
                })));
    }

    @Test
    void partialStrictAlbumDoesNotAutomaticallyResendFragments(VertxTestContext context) {
        when(repository.complete("10", 9001)).thenReturn(Future.succeededFuture());
        when(repository.fail(eq("11"), eq(false), eq(0L), eq("PARTIAL_ALBUM_DELIVERY"), anyString()))
                .thenReturn(Future.succeededFuture());
        var failure = new CloudArchiveService.PartialDelivery(Map.of(10L, 9001L),
                Map.of(11L, new TelegramRunException(new org.drinkless.tdlib.TdApi.Error(403, "FORBIDDEN"))));
        new AutoCloudArchiveVerticle().handleFailure(List.of(record(10, 777), record(11, 777)), failure)
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    verify(repository).complete("10", 9001);
                    verify(repository).fail(eq("11"), eq(false), eq(0L), eq("PARTIAL_ALBUM_DELIVERY"), anyString());
                    context.completeNow();
                })));
    }

    @Test
    void partialStrictAlbumRetainsAccountWaitWithoutRetryingTheFragment(VertxTestContext context) {
        long earliestWaitUntil = System.currentTimeMillis() + 62_000;
        when(repository.complete("10", 9001)).thenReturn(Future.succeededFuture());
        when(repository.fail(eq("11"), eq(false), anyLong(), eq("PARTIAL_ALBUM_DELIVERY"), anyString()))
                .thenReturn(Future.succeededFuture());
        var failure = new CloudArchiveService.PartialDelivery(Map.of(10L, 9001L),
                Map.of(11L, new TelegramRunException(new org.drinkless.tdlib.TdApi.Error(429, "FLOOD_WAIT_60"))));
        new AutoCloudArchiveVerticle().handleFailure(List.of(record(10, 777), record(11, 777)), failure)
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    verify(repository).fail(eq("11"), eq(false), longThat(wait -> wait >= earliestWaitUntil),
                            eq("PARTIAL_ALBUM_DELIVERY"), anyString());
                    assertTrue(AutoCloudArchiveVerticle.ACCOUNT_COOLDOWN_UNTIL.get(7L) >= earliestWaitUntil);
                    context.completeNow();
                })));
    }

    private static CloudArchiveRecord record(long id, long album) {
        return new CloudArchiveRecord(Long.toString(id), 7, 100, 0, id, album, 200, 0, null,
                null, "COPY", "MERGE", null, id, "PENDING", 0, 1_000, null, null, 1_000, 1_000);
    }
}
