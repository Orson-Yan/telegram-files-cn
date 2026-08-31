package telegram.files.maintains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadStatusMaintainVerticleTest {

    @TempDir
    Path appRoot;

    @Test
    void acceptsOnlyExistingExactSizeFilesInsideAppRoot() throws Exception {
        Path file = appRoot.resolve("account/photo.jpg");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "fixture");
        var current = new DownloadStatusMaintainVerticle.CurrentRecord(
                "unique-id", Files.size(file), "photo");
        var previous = new DownloadStatusMaintainVerticle.BackupRecord(
                "unique-id", file.toString(), Files.size(file), 100L, 200L);

        assertTrue(DownloadStatusMaintainVerticle
                .validateCandidate(appRoot, current, previous)
                .recoverable());
        assertFalse(DownloadStatusMaintainVerticle
                .validateCandidate(
                        appRoot,
                        current,
                        new DownloadStatusMaintainVerticle.BackupRecord(
                                "unique-id", file.toString(), Files.size(file) + 1, 100L, 200L)
                )
                .recoverable());
        assertFalse(DownloadStatusMaintainVerticle
                .validateCandidate(
                        appRoot,
                        current,
                        new DownloadStatusMaintainVerticle.BackupRecord(
                                "unique-id",
                                appRoot.resolveSibling("outside.jpg").toString(),
                                Files.size(file),
                                100L,
                                200L)
                )
                .recoverable());
    }
}
