package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.Definition;
import telegram.files.repository.FileRecord;
import telegram.files.repository.impl.AbstractSqlRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Durable discovery checkpoints and per-message download receipts. */
public final class ChannelDownloadStore extends AbstractSqlRepository {
    public ChannelDownloadStore(SqlClient client) { super(client); }

    public static final class Sources implements Definition {
        public String getScheme() { return """
            CREATE TABLE IF NOT EXISTS channel_download_source (
              id VARCHAR(100) PRIMARY KEY, account_id BIGINT NOT NULL, chat_id BIGINT NOT NULL,
              title VARCHAR(512) NOT NULL, rule_json TEXT NOT NULL, rule_version INT NOT NULL DEFAULT 1,
              enabled INT NOT NULL DEFAULT 1, paused INT NOT NULL DEFAULT 0,
              initialized INT NOT NULL DEFAULT 0, boundary_id BIGINT NOT NULL DEFAULT 0,
              history_cursor BIGINT NOT NULL DEFAULT 0, history_done INT NOT NULL DEFAULT 0,
              live_watermark BIGINT NOT NULL DEFAULT 0, live_cursor BIGINT NOT NULL DEFAULT 0,
              live_target BIGINT NOT NULL DEFAULT 0, scanned_count BIGINT NOT NULL DEFAULT 0,
              last_check BIGINT NOT NULL DEFAULT 0, next_scan_at BIGINT NOT NULL DEFAULT 0,
              last_error VARCHAR(1024), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
            )
            """; }
    }

    public static final class Jobs implements Definition {
        public String getScheme() { return """
            CREATE TABLE IF NOT EXISTS channel_download_job (
              id VARCHAR(64) PRIMARY KEY, source_id VARCHAR(100) NOT NULL,
              account_id BIGINT NOT NULL, chat_id BIGINT NOT NULL, message_id BIGINT NOT NULL,
              unique_id VARCHAR(255) NOT NULL, rule_version INT NOT NULL,
              status VARCHAR(24) NOT NULL, priority INT NOT NULL, file_type VARCHAR(32),
              file_name VARCHAR(1024), file_size BIGINT NOT NULL DEFAULT 0,
              sent_at BIGINT NOT NULL DEFAULT 0, caption TEXT, local_path VARCHAR(2048),
              attempts INT NOT NULL DEFAULT 0, next_attempt BIGINT NOT NULL DEFAULT 0,
              last_error VARCHAR(1024), updated_at BIGINT NOT NULL
            )
            """; }
        @Override public Future<Void> createTable(SqlClient client) {
            return Definition.super.createTable(client).compose(_ -> client.query("""
                CREATE INDEX idx_channel_download_due ON channel_download_job(status, next_attempt, account_id)
                """).execute().recover(_ -> Future.succeededFuture()).mapEmpty())
                .compose(_ -> client.query("CREATE INDEX idx_channel_download_source ON channel_download_job(source_id,rule_version,status)")
                    .execute().recover(_ -> Future.succeededFuture()).mapEmpty());
        }
    }
    public static final class Threads implements Definition {
        public String getScheme() { return """
            CREATE TABLE IF NOT EXISTS channel_download_thread (
              id VARCHAR(64) PRIMARY KEY, source_id VARCHAR(100) NOT NULL,
              parent_chat BIGINT NOT NULL,parent_message BIGINT NOT NULL,rule_version INT NOT NULL,
              cursor_id BIGINT NOT NULL DEFAULT 0,watermark BIGINT NOT NULL DEFAULT 0,
              target_id BIGINT NOT NULL DEFAULT 0,done INT NOT NULL DEFAULT 0,last_check BIGINT NOT NULL DEFAULT 0
            )
            """; }
    }

    Future<RowSet<Row>> query(String sql, Object... values) {
        return preparedQuery(sql).execute(Tuple.from(java.util.Arrays.asList(values)));
    }
    public Future<List<JsonObject>> sources() {
        return query("SELECT * FROM channel_download_source ORDER BY last_check, created_at").map(ChannelDownloadStore::rows);
    }
    public Future<JsonObject> source(String id) {
        return query("SELECT * FROM channel_download_source WHERE id=?", id).map(r -> r.size() == 0 ? null : json(r.iterator().next()));
    }
    public Future<Void> ensure(long account, long chat, String title, String rule, boolean enabled) {
        String id = account + ":" + chat;
        long now = System.currentTimeMillis();
        return source(id).compose(old -> {
            if (old == null) return query("""
                INSERT INTO channel_download_source(id,account_id,chat_id,title,rule_json,enabled,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, account, chat, title, rule, enabled ? 1 : 0, now, now).mapEmpty();
            if (!rule.equals(old.getString("rule_json"))) return query("""
                UPDATE channel_download_source SET rule_json=?,rule_version=rule_version+1,
                  history_cursor=0,history_done=?,live_cursor=0,live_target=0,
                  live_watermark=boundary_id,scanned_count=0,enabled=?,title=?,last_error=NULL,updated_at=? WHERE id=?
                """, rule, new JsonObject(rule).getBoolean("downloadHistory",false)?0:1, enabled ? 1 : 0, title, now, id).mapEmpty();
            return query("UPDATE channel_download_source SET enabled=?,title=? WHERE id=?", enabled ? 1 : 0, title, id).mapEmpty();
        });
    }
    public Future<Void> initialize(JsonObject source, long head, boolean history) {
        return query("""
            UPDATE channel_download_source SET initialized=1,boundary_id=?,live_watermark=?,history_done=?,updated_at=?
            WHERE id=? AND initialized=0 AND rule_version=?
            """, head, head, history ? 0 : 1, System.currentTimeMillis(), source.getString("id"), n(source,"rule_version")).mapEmpty();
    }
    public Future<Void> page(JsonObject source, List<FileRecord> files, boolean live, long cursor, long target, boolean done, int scanned) {
        return DataVerticle.pool.withTransaction(connection -> {
            ChannelDownloadStore tx = new ChannelDownloadStore(connection);
            return tx.source(source.getString("id")).compose(current -> {
                if (current == null || n(current,"rule_version") != n(source,"rule_version")
                    || n(current,"enabled") == 0 || n(current,"paused") != 0) return Future.succeededFuture();
                Future<Void> work = Future.succeededFuture();
                for (FileRecord file : files) work = work.compose(_ -> tx.enqueue(source, file, live));
                long now = System.currentTimeMillis();
                return work.compose(_ -> live
                    ? tx.query("""
                        UPDATE channel_download_source SET live_cursor=?,live_target=?,live_watermark=?,
                        last_check=?,next_scan_at=0,last_error=NULL,updated_at=? WHERE id=? AND rule_version=?
                        """, done ? 0 : cursor, done ? 0 : target, done ? target : n(source,"live_watermark"),
                        now, now, source.getString("id"), n(source,"rule_version")).mapEmpty()
                    : tx.query("""
                        UPDATE channel_download_source SET history_cursor=?,history_done=?,scanned_count=scanned_count+?,
                        last_check=?,next_scan_at=0,last_error=NULL,updated_at=? WHERE id=? AND rule_version=?
                        """, cursor, done ? 1 : 0, scanned, now, now, source.getString("id"), n(source,"rule_version")).mapEmpty());
            });
        });
    }
    Future<Void> enqueue(JsonObject source, FileRecord file, boolean live) {
        String id = jobId(n(source,"account_id"), file.chatId(), file.messageId(), file.uniqueId());
        return query("SELECT status FROM channel_download_job WHERE id=?", id).compose(found -> {
            if (found.size() > 0) return query("UPDATE channel_download_job SET rule_version=? WHERE id=?",
                    n(source,"rule_version"), id).mapEmpty();
            return query("""
                INSERT INTO channel_download_job(id,source_id,account_id,chat_id,message_id,unique_id,rule_version,
                  status,priority,file_type,file_name,file_size,sent_at,caption,updated_at)
                VALUES(?,?,?,?,?,?,?,'WAITING',?,?,?,?,?,?,?) %s
                """.formatted(Config.isMysql()?"ON DUPLICATE KEY UPDATE rule_version=VALUES(rule_version)"
                    :"ON CONFLICT(id) DO UPDATE SET rule_version=excluded.rule_version"), id, source.getString("id"), n(source,"account_id"), file.chatId(), file.messageId(), file.uniqueId(),
                n(source,"rule_version"), live ? 100 : 10, file.type(), file.fileName(), file.size(), file.date(),
                file.caption(), System.currentTimeMillis()).mapEmpty();
        }).compose(_ -> new JsonObject(source.getString("rule_json")).getBoolean("downloadCommentFiles",false)
            && file.chatId()==n(source,"chat_id") ? thread(source,file.chatId(),file.messageId()) : Future.succeededFuture());
    }
    public Future<List<JsonObject>> due(long account, int limit) {
        return due(account,limit,false);
    }
    public Future<List<JsonObject>> due(long account, int limit, boolean preferHistory) {
        return query("""
            SELECT j.* FROM channel_download_job j JOIN channel_download_source s ON s.id=j.source_id
            WHERE j.account_id=? AND s.enabled=1 AND s.paused=0 AND j.rule_version=s.rule_version
              AND j.status IN ('WAITING','RETRY') AND j.next_attempt<=?
            ORDER BY s.updated_at,j.priority %s,j.attempts,j.updated_at,j.id LIMIT ?
            """.formatted(preferHistory?"ASC":"DESC"), account, System.currentTimeMillis(), limit).map(ChannelDownloadStore::rows);
    }
    public Future<Boolean> claim(JsonObject job) {
        return query("""
            UPDATE channel_download_job SET status='STARTING',attempts=attempts+1,updated_at=?
            WHERE id=? AND status IN ('WAITING','RETRY') AND rule_version=?
              AND EXISTS (SELECT 1 FROM channel_download_source s WHERE s.id=channel_download_job.source_id
                AND s.enabled=1 AND s.paused=0 AND s.rule_version=channel_download_job.rule_version)
            """, System.currentTimeMillis(), job.getString("id"), n(job,"rule_version")).compose(r -> {
                if(r.rowCount()!=1) return Future.succeededFuture(false);
                return query("UPDATE channel_download_source SET updated_at=? WHERE id=?",System.currentTimeMillis(),job.getString("source_id")).map(true);
            });
    }
    public Future<Void> candidates(JsonObject source,List<FileRecord> files,boolean live) {
        return DataVerticle.pool.withTransaction(connection -> {
            ChannelDownloadStore tx=new ChannelDownloadStore(connection);
            return tx.source(source.getString("id")).compose(current -> {
                if(!ChannelDownloadVerticle.active(current) || n(current,"rule_version")!=n(source,"rule_version"))
                    return Future.succeededFuture();
                Future<Void> work=Future.succeededFuture();
                for(FileRecord file:files) work=work.compose(_ -> tx.enqueue(source,file,live));
                return work;
            });
        });
    }
    public Future<Void> thread(JsonObject source,long parentChat,long parentMessage) {
        String id=jobId(n(source,"account_id"),parentChat,parentMessage,source.getString("id"));
        return query("SELECT * FROM channel_download_thread WHERE id=?",id).compose(rows -> {
            if(rows.size()>0) return query("""
                UPDATE channel_download_thread SET rule_version=?,cursor_id=0,watermark=0,target_id=0,done=0
                WHERE id=? AND rule_version<>?
                """,n(source,"rule_version"),id,n(source,"rule_version")).mapEmpty();
            return query("""
                INSERT INTO channel_download_thread(id,source_id,parent_chat,parent_message,rule_version)
                VALUES(?,?,?,?,?)
                """,id,source.getString("id"),parentChat,parentMessage,n(source,"rule_version")).mapEmpty();
        });
    }
    public Future<List<JsonObject>> threads() {
        return query("""
            SELECT t.* FROM channel_download_thread t JOIN channel_download_source s ON s.id=t.source_id
            WHERE s.enabled=1 AND s.paused=0 AND t.rule_version=s.rule_version
              AND ((t.done=0 AND t.last_check<?) OR (t.done=1 AND t.last_check<?)) ORDER BY t.last_check LIMIT 1
            """,System.currentTimeMillis(),System.currentTimeMillis()-30_000).map(ChannelDownloadStore::rows);
    }
    public Future<Void> threadPage(JsonObject source,JsonObject thread,List<FileRecord> files,long cursor,long target,boolean done) {
        return DataVerticle.pool.withTransaction(connection -> {
            ChannelDownloadStore tx=new ChannelDownloadStore(connection);
            return tx.source(source.getString("id")).compose(current -> {
                if(!ChannelDownloadVerticle.active(current) || n(current,"rule_version")!=n(thread,"rule_version")) return Future.succeededFuture();
                Future<Void> work=Future.succeededFuture();
                for(FileRecord file:files) work=work.compose(_ -> tx.enqueue(source,file,n(thread,"watermark")>0));
                return work.compose(_ -> tx.query("""
                    UPDATE channel_download_thread SET cursor_id=?,target_id=?,watermark=?,done=?,last_check=? WHERE id=? AND rule_version=?
                    """,done?0:cursor,done?0:target,done?target:n(thread,"watermark"),done?1:0,System.currentTimeMillis(),
                    thread.getString("id"),n(thread,"rule_version")).mapEmpty());
            });
        });
    }
    public Future<Void> finish(String id, String status, String error, long retryAt, String path) {
        String safe = error == null ? null : error.substring(0, Math.min(1000,error.length()));
        return query("""
            UPDATE channel_download_job SET status=?,last_error=?,next_attempt=?,local_path=COALESCE(?,local_path),updated_at=? WHERE id=?
            """, status, safe, retryAt, path, System.currentTimeMillis(), id).mapEmpty();
    }
    public Future<List<JsonObject>> running() {
        return query("SELECT * FROM channel_download_job WHERE status IN ('STARTING','RUNNING','PAUSED') ORDER BY updated_at")
                .map(ChannelDownloadStore::rows);
    }
    public Future<Void> error(String source,long version, String error, long retryAt) {
        return query("UPDATE channel_download_source SET last_error=?,next_scan_at=?,last_check=? WHERE id=? AND rule_version=?",
                error.substring(0,Math.min(error.length(),1000)), retryAt, System.currentTimeMillis(), source,version).mapEmpty();
    }
    public Future<Void> action(String id, String action) {
        return switch (action) {
            case "pause", "resume", "rescan" -> control(id,action);
            case "retry" -> query("""
                UPDATE channel_download_job SET status='RETRY',attempts=0,next_attempt=0,last_error=NULL
                WHERE source_id=? AND status IN ('FAILED','RETRY')
                """, id).mapEmpty();
            default -> Future.failedFuture(new IllegalArgumentException("Unknown download action"));
        };
    }
    private Future<Void> control(String id,String action) {
        return DataVerticle.pool.withTransaction(connection -> {
            ChannelDownloadStore tx=new ChannelDownloadStore(connection);
            return tx.source(id).compose(source -> {
                if(source==null) return Future.failedFuture(new IllegalArgumentException("Download task not found"));
                long version=n(source,"rule_version");
                int rescan="rescan".equals(action)?1:0;
                int paused="pause".equals(action)?1:"resume".equals(action)?0:(int)n(source,"paused");
                return tx.query("""
                    UPDATE channel_download_source SET paused=?,rule_version=rule_version+1,last_error=NULL,
                      next_scan_at=0,last_check=0,live_cursor=0,live_target=-1,history_cursor=CASE WHEN ?=1 THEN 0 ELSE history_cursor END,
                      history_done=CASE WHEN ?=1 THEN 0 ELSE history_done END WHERE id=? AND rule_version=?
                    """,paused,rescan,rescan,id,version)
                    .compose(_ -> tx.query("UPDATE channel_download_job SET rule_version=? WHERE source_id=? AND rule_version=?",version+1,id,version))
                    .compose(_ -> tx.query(rescan==1
                        ?"UPDATE channel_download_thread SET rule_version=?,cursor_id=0,watermark=0,target_id=0,done=0,last_check=0 WHERE source_id=? AND rule_version=?"
                        :"UPDATE channel_download_thread SET rule_version=? WHERE source_id=? AND rule_version=?",version+1,id,version))
                    .compose(_ -> rescan==0?Future.succeededFuture():tx.query("""
                        UPDATE channel_download_job SET status='WAITING',attempts=0,next_attempt=0,last_error=NULL
                        WHERE source_id=? AND rule_version=? AND status IN ('FAILED','RETRY','SKIPPED')
                        """,id,version+1).mapEmpty()).mapEmpty();
            });
        });
    }
    public Future<JsonArray> overview() {
        return query("""
            SELECT s.*, j.status AS job_status, COUNT(j.id) AS job_count, COALESCE(SUM(j.file_size),0) AS job_bytes,
                   MAX(j.updated_at) AS last_job_at,
                   MAX(CASE WHEN j.status='COMPLETED' THEN j.updated_at ELSE 0 END) AS last_success_at,
                   (SELECT COUNT(*) FROM channel_download_thread t WHERE t.source_id=s.id AND t.rule_version=s.rule_version AND t.done=0) AS comment_scans,
                   (SELECT COUNT(*) FROM channel_download_thread t WHERE t.source_id=s.id AND t.rule_version=s.rule_version AND t.done=2) AS comment_errors
                   FROM channel_download_source s
            LEFT JOIN channel_download_job j ON s.id=j.source_id AND s.rule_version=j.rule_version
            GROUP BY s.id,s.account_id,s.chat_id,s.title,s.rule_json,s.rule_version,s.enabled,s.paused,s.initialized,
              s.boundary_id,s.history_cursor,s.history_done,s.live_watermark,s.live_cursor,s.live_target,s.scanned_count,
              s.last_check,s.next_scan_at,s.last_error,s.created_at,s.updated_at,j.status
            ORDER BY s.created_at DESC
            """).map(result -> {
                java.util.Map<String,JsonObject> items = new java.util.LinkedHashMap<>();
                for (Row row : result) {
                    JsonObject entry = items.computeIfAbsent(row.getString("id"), _ -> json(row)
                        .put("account_id", String.valueOf(row.getValue("account_id")))
                        .put("chat_id", String.valueOf(row.getValue("chat_id")))
                        .put("counts",new JsonObject()).put("bytes",new JsonObject()));
                    if (row.getString("job_status") != null) {
                        entry.getJsonObject("counts").put(row.getString("job_status"), ((Number)row.getValue("job_count")).longValue());
                        entry.getJsonObject("bytes").put(row.getString("job_status"), ((Number)row.getValue("job_bytes")).longValue());
                    }
                    entry.put("last_success_at",Math.max(n(entry,"last_success_at"),((Number)row.getValue("last_success_at")).longValue()));
                }
                return new JsonArray(new ArrayList<>(items.values()));
            });
    }
    public Future<JsonArray> manifest(String source) {
        return query("""
            SELECT j.*,f.transfer_status FROM channel_download_job j LEFT JOIN file_record f
              ON f.unique_id=j.unique_id WHERE j.source_id=? ORDER BY j.sent_at,j.message_id
            """, source).map(r -> new JsonArray(rows(r).stream().map(j -> j
                .put("account_id",String.valueOf(j.getValue("account_id")))
                .put("chat_id",String.valueOf(j.getValue("chat_id")))
                .put("message_id",String.valueOf(j.getValue("message_id")))).toList()));
    }
    public static long n(JsonObject object, String key) { return ((Number)object.getValue(key,0L)).longValue(); }
    static JsonObject json(Row row) {
        JsonObject object = new JsonObject();
        for (int i=0;i<row.size();i++) object.put(row.getColumnName(i),row.getValue(i));
        return object;
    }
    static List<JsonObject> rows(RowSet<Row> rows) {
        List<JsonObject> list = new ArrayList<>();
        rows.forEach(row -> list.add(json(row)));
        return list;
    }
    static String jobId(long account,long chat,long message,String unique) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((account+":"+chat+":"+message+":"+unique).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
