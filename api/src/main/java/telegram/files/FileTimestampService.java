package telegram.files;

import cn.hutool.core.util.StrUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import telegram.files.repository.FileRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/** Keeps the filesystem modification time aligned with the Telegram message timestamp. */
public final class FileTimestampService {

    private static final long FILESYSTEM_TIME_TOLERANCE_MILLIS = 1_000L;

    private FileTimestampService() {
    }

    public static Future<Result> applyAsync(Vertx vertx, FileRecord fileRecord, String localPath) {
        Objects.requireNonNull(vertx, "vertx");
        return vertx.executeBlocking(() -> applyBlocking(fileRecord, localPath), false);
    }

    public static Result applyBlocking(FileRecord fileRecord, String localPath) throws IOException {
        Objects.requireNonNull(fileRecord, "fileRecord");
        if ("thumbnail".equals(fileRecord.type())) {
            return Result.skipped(localPath, "thumbnail");
        }
        if (fileRecord.date() <= 0) {
            return Result.skipped(localPath, "invalid-message-time");
        }
        if (StrUtil.isBlank(localPath)) {
            return Result.skipped(localPath, "blank-path");
        }

        Path path = Path.of(localPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Result.skipped(path.toString(), "missing-or-non-regular-file");
        }

        long targetMillis = Math.multiplyExact((long) fileRecord.date(), 1_000L);
        long previousMillis = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        if (Math.abs(previousMillis - targetMillis) < FILESYSTEM_TIME_TOLERANCE_MILLIS) {
            return new Result(path.toString(), previousMillis, targetMillis, false, null);
        }

        Files.setLastModifiedTime(path, FileTime.fromMillis(targetMillis));
        return new Result(path.toString(), previousMillis, targetMillis, true, null);
    }

    public static boolean restoreBlocking(String localPath,
                                          long expectedCurrentMillis,
                                          long previousMillis) throws IOException {
        if (StrUtil.isBlank(localPath) || previousMillis < 0) {
            return false;
        }
        Path path = Path.of(localPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        long currentMillis = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        if (Math.abs(currentMillis - expectedCurrentMillis) >= FILESYSTEM_TIME_TOLERANCE_MILLIS) {
            return false;
        }
        Files.setLastModifiedTime(path, FileTime.fromMillis(previousMillis));
        return true;
    }

    public record Result(String path,
                         long previousModifiedMillis,
                         long targetModifiedMillis,
                         boolean changed,
                         String skippedReason) {

        static Result skipped(String path, String reason) {
            return new Result(path, 0L, 0L, false, reason);
        }

        public boolean skipped() {
            return skippedReason != null;
        }
    }
}
