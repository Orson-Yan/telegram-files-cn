package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.FileRecord;
import telegram.files.repository.TelegramRecord;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class DownloadRecoveryTest {

    @Test
    void requeuesStaleDownloadsReleasesFailedRetriesAndProtectsActiveDownloads(
            Vertx vertx,
            VertxTestContext testContext
    ) {
        AtomicReference<String> deploymentId = new AtomicReference<>();
        long staleStart = System.currentTimeMillis()
                          - TelegramVerticle.STALE_DOWNLOAD_RETRY_AFTER_MILLIS - 1_000;
        FileRecord requeuedRecord = downloadingRecord(77, "stale-requeue", staleStart);
        FileRecord releasedRecord = downloadingRecord(78, "stale-release", staleStart);
        FileRecord activeRecord = downloadingRecord(79, "active-download", staleStart);

        ScriptedTelegramGateway requeueGateway = new ScriptedTelegramGateway(request ->
                request instanceof TdApi.GetFile getFile
                        ? inactiveFile(requeuedRecord, getFile.fileId)
                        : new TdApi.Ok());
        TelegramVerticle requeueReconciler = reconciler(requeueGateway);

        ScriptedTelegramGateway releaseGateway = new ScriptedTelegramGateway(request -> {
            if (request instanceof TdApi.GetFile getFile) {
                FileRecord record = getFile.fileId == releasedRecord.id() ? releasedRecord : requeuedRecord;
                return inactiveFile(record, getFile.fileId);
            }
            if (request instanceof TdApi.AddFileToDownloads) {
                return new TdApi.Error(500, "fixture retry failure");
            }
            return new TdApi.Ok();
        });
        TelegramVerticle releaseReconciler = reconciler(releaseGateway);

        ScriptedTelegramGateway activeGateway = new ScriptedTelegramGateway(request -> {
            TdApi.GetFile getFile = (TdApi.GetFile) request;
            FileRecord record = getFile.fileId == activeRecord.id() ? activeRecord : requeuedRecord;
            TdApi.File file = inactiveFile(record, getFile.fileId);
            file.local.isDownloadingActive = true;
            return file;
        });
        TelegramVerticle activeReconciler = reconciler(activeGateway);

        vertx.deployVerticle(new DataVerticle())
                .onSuccess(deploymentId::set)
                .compose(_ -> DataVerticle.fileRepository.create(requeuedRecord))
                .compose(_ -> requeueReconciler.reconcileDownloadStatuses())
                .compose(_ -> DataVerticle.fileRepository.getByUniqueId(requeuedRecord.uniqueId()))
                .compose(updated -> {
                    testContext.verify(() -> {
                        assertEquals(FileRecord.DownloadStatus.downloading.name(), updated.downloadStatus());
                        assertTrue(updated.startDate() > staleStart);
                        assertTrue(requeueGateway.requests().stream()
                                .anyMatch(TdApi.AddFileToDownloads.class::isInstance));
                    });
                    return DataVerticle.fileRepository.create(releasedRecord);
                })
                .compose(_ -> releaseReconciler.reconcileDownloadStatuses())
                .compose(_ -> DataVerticle.fileRepository.getByUniqueId(releasedRecord.uniqueId()))
                .compose(updated -> {
                    testContext.verify(() -> {
                        assertEquals(FileRecord.DownloadStatus.idle.name(), updated.downloadStatus());
                        assertEquals(0L, updated.startDate());
                    });
                    return DataVerticle.fileRepository.create(activeRecord);
                })
                .compose(_ -> activeReconciler.reconcileDownloadStatuses())
                .compose(_ -> DataVerticle.fileRepository.getByUniqueId(activeRecord.uniqueId()))
                .compose(updated -> {
                    testContext.verify(() -> {
                        assertEquals(staleStart, updated.startDate());
                        assertFalse(activeGateway.requests().stream()
                                .anyMatch(TdApi.AddFileToDownloads.class::isInstance));
                    });
                    return DataVerticle.fileRepository.countByStatus(
                            42, FileRecord.DownloadStatus.downloading);
                })
                .eventually(() -> deploymentId.get() == null
                        ? Future.succeededFuture()
                        : vertx.undeploy(deploymentId.get()).mapEmpty())
                .onComplete(testContext.succeeding(count -> testContext.verify(() -> {
                    assertEquals(2, count);
                    testContext.completeNow();
                })));
    }

    private static TelegramVerticle reconciler(ScriptedTelegramGateway gateway) {
        TelegramVerticle telegramVerticle = new TelegramVerticle("/tmp/account-fixture", () -> gateway);
        telegramVerticle.telegramRecord = new TelegramRecord(42, "test", "test", null);
        telegramVerticle.authorized = true;
        telegramVerticle.client = gateway;
        return telegramVerticle;
    }

    private static FileRecord downloadingRecord(int id, String uniqueId, long startDate) {
        return new FileRecord(
                id, uniqueId, 42, 100, 200 + id, 0, 1_700_000_000, false, 1024, 0,
                "video", "video/mp4", "fixture.mp4", null, null, "", null,
                null, FileRecord.DownloadStatus.downloading.name(),
                FileRecord.TransferStatus.idle.name(), startDate, null, null, 0, 0, 0
        );
    }

    private static TdApi.File inactiveFile(FileRecord record, int fileId) {
        TdApi.LocalFile local = new TdApi.LocalFile("", true, true, false, false, 0, 0, 0);
        TdApi.RemoteFile remote = new TdApi.RemoteFile(
                "remote-" + record.uniqueId(), record.uniqueId(), false, true, 0);
        return new TdApi.File(fileId, record.size(), record.size(), local, remote);
    }
}
