package telegram.files.maintains;

import cn.hutool.core.collection.IterUtil;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.templates.SqlTemplate;
import telegram.files.Config;
import telegram.files.DataVerticle;
import telegram.files.FileTimestampService;
import telegram.files.repository.FileRecord;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies Telegram message timestamps to historical files and writes a reversible JSONL audit.
 */
public final class FileTimeMaintainVerticle extends MaintainVerticle {

    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String operation;

    private final String auditArgument;

    public FileTimeMaintainVerticle(String[] args) {
        this.operation = args.length > 1 ? args[1] : "apply";
        this.auditArgument = args.length > 2 ? args[2] : null;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        super.start(startPromise, this::handle);
    }

    @Override
    protected boolean initializeTelegram() {
        return false;
    }

    private void handle() {
        try {
            switch (operation) {
                case "apply" -> applyMessageTimes();
                case "rollback" -> rollbackMessageTimes();
                default -> throw new IllegalArgumentException(
                        "Usage: tfm file-time [apply | rollback <audit-file>]"
                );
            }
            super.end(true, null);
        } catch (Exception error) {
            log.error("File time maintenance failed", error);
            super.end(false, error);
        }
    }

    private void applyMessageTimes() throws Exception {
        List<FileRecord> records = completedFiles();
        Path auditDirectory = auditDirectory();
        Files.createDirectories(auditDirectory);
        Path auditFile = auditDirectory.resolve(
                "file-time-" + LocalDateTime.now().format(AUDIT_TIME) + ".jsonl"
        );

        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        int failed = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(
                auditFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            for (FileRecord record : records) {
                try {
                    FileTimestampService.Result result =
                            FileTimestampService.applyBlocking(record, record.localPath());
                    if (result.skipped()) {
                        skipped++;
                        continue;
                    }
                    if (!result.changed()) {
                        unchanged++;
                        continue;
                    }
                    writer.write(new JsonObject()
                            .put("path", result.path())
                            .put("previousModifiedMillis", result.previousModifiedMillis())
                            .put("targetModifiedMillis", result.targetModifiedMillis())
                            .put("uniqueId", record.uniqueId())
                            .put("telegramId", record.telegramId())
                            .encode());
                    writer.newLine();
                    updated++;
                    if (updated % 100 == 0) {
                        writer.flush();
                    }
                } catch (Exception error) {
                    failed++;
                    log.warn("Failed to update file time for {}: {}",
                            record.uniqueId(), error.getMessage());
                }
            }
        }

        log.info("File time maintenance completed: scanned={}, updated={}, unchanged={}, skipped={}, failed={}, audit={}",
                records.size(), updated, unchanged, skipped, failed, auditFile);
    }

    private void rollbackMessageTimes() throws Exception {
        if (auditArgument == null || auditArgument.isBlank()) {
            throw new IllegalArgumentException("Usage: tfm file-time rollback <audit-file>");
        }

        Path auditDirectory = auditDirectory();
        Path requested = Path.of(auditArgument);
        Path auditFile = (requested.isAbsolute() ? requested : auditDirectory.resolve(requested))
                .toAbsolutePath()
                .normalize();
        if (!auditFile.startsWith(auditDirectory)
            || !Files.isRegularFile(auditFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Audit file must be inside " + auditDirectory);
        }

        Map<String, FileRecord> currentFiles = new HashMap<>();
        for (FileRecord record : completedFiles()) {
            currentFiles.put(Path.of(record.localPath()).toAbsolutePath().normalize().toString(), record);
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(auditFile, StandardCharsets.UTF_8));
        Collections.reverse(lines);
        int restored = 0;
        int skipped = 0;
        int failed = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject entry = new JsonObject(line);
                String path = Path.of(entry.getString("path")).toAbsolutePath().normalize().toString();
                if (!currentFiles.containsKey(path)) {
                    skipped++;
                    continue;
                }
                boolean changed = FileTimestampService.restoreBlocking(
                        path,
                        entry.getLong("targetModifiedMillis"),
                        entry.getLong("previousModifiedMillis")
                );
                if (changed) {
                    restored++;
                } else {
                    skipped++;
                }
            } catch (Exception error) {
                failed++;
                log.warn("Failed to rollback a file time entry: {}", error.getMessage());
            }
        }

        log.info("File time rollback completed: restored={}, skipped={}, failed={}, audit={}",
                restored, skipped, failed, auditFile);
    }

    private List<FileRecord> completedFiles() {
        return Future.await(SqlTemplate.forQuery(DataVerticle.pool, """
                        SELECT * FROM file_record
                        WHERE download_status = 'completed'
                          AND type != 'thumbnail'
                          AND local_path IS NOT NULL
                          AND local_path != ''
                          AND date > 0
                        ORDER BY telegram_id, unique_id
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of())
                .map(IterUtil::toList));
    }

    private Path auditDirectory() {
        return Path.of(Config.APP_ROOT, "maintenance-audits").toAbsolutePath().normalize();
    }
}
