package telegram.files.maintains;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import telegram.files.Config;
import telegram.files.DataVerticle;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restores download metadata that was incorrectly downgraded from completed to idle.
 *
 * <p>The backup is treated only as metadata evidence. A row is recoverable only when the current
 * row is idle, the backup row was completed, and the referenced local file still exists under
 * {@link Config#APP_ROOT} with the exact recorded size.</p>
 */
public final class DownloadStatusMaintainVerticle extends MaintainVerticle {

    private static final DateTimeFormatter AUDIT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int UPDATE_BATCH_SIZE = 500;

    private final String operation;
    private final String backupArgument;

    public DownloadStatusMaintainVerticle(String[] args) {
        this.operation = args.length > 1 ? args[1] : "scan";
        this.backupArgument = args.length > 2 ? args[2] : null;
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
                case "scan" -> recover(false);
                case "apply" -> recover(true);
                default -> throw usage();
            }
            super.end(true, null);
        } catch (Exception error) {
            log.error("Download status maintenance failed", error);
            super.end(false, error);
        }
    }

    private void recover(boolean apply) throws Exception {
        Path appRoot = Path.of(Config.APP_ROOT).toAbsolutePath().normalize();
        Path backup = resolveBackup(appRoot);
        Map<String, BackupRecord> backupRecords = readBackup(backup);
        List<CurrentRecord> idleRecords = idleRecords();
        List<RecoveryRecord> recoverable = new ArrayList<>();
        Map<String, Integer> skipped = new HashMap<>();
        int matched = 0;

        for (CurrentRecord current : idleRecords) {
            BackupRecord previous = backupRecords.get(current.uniqueId());
            if (previous == null) {
                skipped.merge("not-in-backup", 1, Integer::sum);
                continue;
            }
            matched++;
            Validation validation = validateCandidate(appRoot, current, previous);
            if (!validation.recoverable()) {
                skipped.merge(validation.reason(), 1, Integer::sum);
                continue;
            }
            recoverable.add(new RecoveryRecord(current, previous));
        }

        if (apply && !recoverable.isEmpty()) {
            Path pendingAudit = writePendingAudit(recoverable, backup);
            applyRecovery(recoverable);
            finalizeAudit(pendingAudit);
        }

        log.info(
                "Download status maintenance completed: mode={}, idle={}, matched={}, recoverable={}, skipped={}, backup={}",
                apply ? "apply" : "scan",
                idleRecords.size(),
                matched,
                recoverable.size(),
                skipped,
                backup
        );
    }

    private Path resolveBackup(Path appRoot) {
        if (backupArgument == null || backupArgument.isBlank()) {
            throw usage();
        }
        Path requested = Path.of(backupArgument);
        Path backup = (requested.isAbsolute() ? requested : appRoot.resolve(requested))
                .toAbsolutePath()
                .normalize();
        Path current = Path.of(DataVerticle.getDataPath()).toAbsolutePath().normalize();
        if (!backup.startsWith(appRoot)
            || backup.equals(current)
            || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Backup database must be a regular file inside " + appRoot
            );
        }
        return backup;
    }

    private Map<String, BackupRecord> readBackup(Path backup) throws Exception {
        Map<String, BackupRecord> records = new HashMap<>();
        String jdbcPath = backup.toString().replace('\\', '/');
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:file:" + jdbcPath + "?mode=ro");
             var statement = connection.prepareStatement("""
                     SELECT unique_id, local_path, size, start_date, completion_date
                     FROM file_record
                     WHERE download_status = 'completed'
                       AND type != 'thumbnail'
                       AND local_path IS NOT NULL
                       AND local_path != ''
                     """);
             var result = statement.executeQuery()) {
            while (result.next()) {
                records.put(result.getString("unique_id"), new BackupRecord(
                        result.getString("unique_id"),
                        result.getString("local_path"),
                        result.getLong("size"),
                        result.getLong("start_date"),
                        (Long) result.getObject("completion_date")
                ));
            }
        }
        return records;
    }

    private List<CurrentRecord> idleRecords() {
        RowSet<Row> rows = Future.await(DataVerticle.pool.query("""
                SELECT unique_id, size, type
                FROM file_record
                WHERE download_status = 'idle'
                  AND type != 'thumbnail'
                ORDER BY telegram_id, unique_id
                """).execute());
        List<CurrentRecord> records = new ArrayList<>(rows.size());
        for (Row row : rows) {
            records.add(new CurrentRecord(
                    row.getString("unique_id"),
                    row.getLong("size"),
                    row.getString("type")
            ));
        }
        return records;
    }

    private Path writePendingAudit(List<RecoveryRecord> records, Path backup) throws Exception {
        Path auditDirectory = Path.of(Config.APP_ROOT, "maintenance-audits")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(auditDirectory);
        Path audit = auditDirectory.resolve(
                "download-status-recovery-"
                + LocalDateTime.now().format(AUDIT_TIME)
                + ".jsonl"
        );
        Path pending = audit.resolveSibling(audit.getFileName() + ".pending");
        try (BufferedWriter writer = Files.newBufferedWriter(
                pending,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            writer.write(new JsonObject()
                    .put("kind", "metadata")
                    .put("backup", backup.toString())
                    .put("count", records.size())
                    .encode());
            writer.newLine();
            for (RecoveryRecord record : records) {
                writer.write(new JsonObject()
                        .put("kind", "record")
                        .put("uniqueId", record.current().uniqueId())
                        .put("size", record.current().size())
                        .put("type", record.current().type())
                        .put("restoredLocalPath", record.previous().localPath())
                        .put("restoredStartDate", record.previous().startDate())
                        .put("restoredCompletionDate", record.previous().completionDate())
                        .encode());
                writer.newLine();
            }
        }
        return pending;
    }

    private void applyRecovery(List<RecoveryRecord> records) {
        Future.await(DataVerticle.pool.withTransaction(client -> {
            Future<Void> chain = Future.succeededFuture();
            for (int from = 0; from < records.size(); from += UPDATE_BATCH_SIZE) {
                int to = Math.min(records.size(), from + UPDATE_BATCH_SIZE);
                List<Tuple> batch = records.subList(from, to).stream()
                        .map(record -> Tuple.of(
                                record.previous().localPath(),
                                record.previous().startDate(),
                                record.previous().completionDate(),
                                record.current().uniqueId()
                        ))
                        .toList();
                chain = chain.compose(_ -> client.preparedQuery("""
                                UPDATE file_record
                                SET local_path = ?,
                                    download_status = 'completed',
                                    start_date = ?,
                                    completion_date = ?
                                WHERE unique_id = ?
                                  AND download_status = 'idle'
                                """)
                        .executeBatch(batch)
                        .mapEmpty());
            }
            return chain;
        }));
    }

    private void finalizeAudit(Path pending) throws Exception {
        String name = pending.getFileName().toString();
        Path audit = pending.resolveSibling(name.substring(0, name.length() - ".pending".length()));
        try {
            Files.move(pending, audit, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, audit);
        }
        log.info("Download status recovery audit written: {}", audit);
    }

    static Validation validateCandidate(
            Path appRoot,
            CurrentRecord current,
            BackupRecord previous
    ) {
        if (current.size() != previous.size()) {
            return new Validation(false, "database-size-mismatch");
        }
        Path path;
        try {
            path = Path.of(previous.localPath()).toAbsolutePath().normalize();
        } catch (Exception error) {
            return new Validation(false, "invalid-path");
        }
        Path normalizedRoot = appRoot.toAbsolutePath().normalize();
        if (!path.startsWith(normalizedRoot)) {
            return new Validation(false, "unsafe-path");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Validation(false, "missing-file");
        }
        try {
            if (Files.size(path) != previous.size()) {
                return new Validation(false, "disk-size-mismatch");
            }
        } catch (Exception error) {
            return new Validation(false, "unreadable-file");
        }
        return new Validation(true, "");
    }

    private IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: tfm download-status [scan | apply] <backup-db>"
        );
    }

    record CurrentRecord(String uniqueId, long size, String type) {}

    record BackupRecord(
            String uniqueId,
            String localPath,
            long size,
            long startDate,
            Long completionDate
    ) {}

    record RecoveryRecord(CurrentRecord current, BackupRecord previous) {}

    record Validation(boolean recoverable, String reason) {}
}
