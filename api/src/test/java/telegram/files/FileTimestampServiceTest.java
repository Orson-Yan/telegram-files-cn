package telegram.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.files.repository.FileRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTimestampServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void appliesTelegramMessageTimeAndCanRollback() throws Exception {
        Path file = tempDirectory.resolve("photo.jpg");
        Files.writeString(file, "fixture");
        long previousMillis = 1_750_000_000_000L;
        Files.setLastModifiedTime(file, FileTime.fromMillis(previousMillis));
        FileRecord record = record("photo", 1_700_000_000);

        FileTimestampService.Result result =
                FileTimestampService.applyBlocking(record, file.toString());

        assertTrue(result.changed());
        assertEquals(previousMillis, result.previousModifiedMillis());
        assertEquals(1_700_000_000_000L, Files.getLastModifiedTime(file).toMillis());

        FileTimestampService.Result second =
                FileTimestampService.applyBlocking(record, file.toString());
        assertFalse(second.changed());
        assertTrue(FileTimestampService.restoreBlocking(
                file.toString(),
                result.targetModifiedMillis(),
                result.previousModifiedMillis()
        ));
        assertEquals(previousMillis, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void skipsThumbnailsAndMissingFiles() throws Exception {
        Path missing = tempDirectory.resolve("missing.jpg");

        assertTrue(FileTimestampService.applyBlocking(
                record("thumbnail", 1_700_000_000), missing.toString()).skipped());
        assertTrue(FileTimestampService.applyBlocking(
                record("photo", 1_700_000_000), missing.toString()).skipped());
    }

    private static FileRecord record(String type, int messageDate) {
        return new FileRecord(
                1, "unique-id", 1, 2, 3, 0, messageDate, false, 7, 0,
                type, "image/jpeg", "photo.jpg", null, null, "", null,
                null, FileRecord.DownloadStatus.completed.name(),
                FileRecord.TransferStatus.idle.name(), 0, 1L, null, 0, 0, 0
        );
    }
}
