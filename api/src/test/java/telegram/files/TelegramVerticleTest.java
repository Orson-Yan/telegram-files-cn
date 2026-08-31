package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.files.repository.FileRecord;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TelegramVerticleTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void resetFactory() {
        TelegramVerticles.resetTelegramGatewayFactory();
    }

    @Test
    void usesConfiguredGatewayAndForwardsAuthorizationUpdates() {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(_ -> new TdApi.Ok());
        TelegramVerticles.configureTelegramGatewayFactory(() -> gateway);

        TelegramVerticle verticle = TelegramVerticles.create("/tmp/account-fixture");
        verticle.initializeTelegramGateway();
        gateway.emit(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateClosing()));

        assertSame(gateway, verticle.tdlibClient());
        assertInstanceOf(TdApi.AuthorizationStateClosing.class, verticle.lastAuthorizationState);
        assertTrue(gateway.requests().isEmpty());
    }

    @Test
    void scriptedGatewayCanModelTdlibErrorsWithoutNativeTelegram() {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(
                _ -> new TdApi.Error(404, "fixture not found")
        );
        gateway.initialize(_ -> { }, _ -> { }, _ -> { });

        assertTrue(gateway.execute(new TdApi.GetMe()).failed());
        assertNull(gateway.execute(new TdApi.GetMe(), true).result());
        assertEquals(2, gateway.requests().size());
    }

    @Test
    void preservesCompletedDownloadWhenItsLocalFileStillExists() throws Exception {
        Path localFile = tempDirectory.resolve("downloaded-photo.jpg");
        Files.writeString(localFile, "fixture");

        assertTrue(TelegramVerticle.shouldPreserveCompletedDownload(
                fileRecord(FileRecord.DownloadStatus.completed, localFile)));
        assertFalse(TelegramVerticle.shouldPreserveCompletedDownload(
                fileRecord(FileRecord.DownloadStatus.idle, localFile)));
        assertFalse(TelegramVerticle.shouldPreserveCompletedDownload(
                fileRecord(FileRecord.DownloadStatus.completed, tempDirectory.resolve("missing.jpg"))));
    }

    private static FileRecord fileRecord(FileRecord.DownloadStatus status, Path localPath) {
        return new FileRecord(
                1, "unique-id", 1, 2, 3, 0, 1_700_000_000, false, 7, 0,
                "photo", "image/jpeg", "photo.jpg", null, null, "", null,
                localPath.toString(), status.name(), FileRecord.TransferStatus.idle.name(),
                0, 1L, null, 0, 0, 0
        );
    }

}
