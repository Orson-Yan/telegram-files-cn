package telegram.files;

import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** One final predicate for historical, live, and recovery candidates. */
public final class DownloadRules {
    private DownloadRules() { }
    public static boolean matches(TdApi.Message message, SettingAutoRecords.DownloadRule rule) {
        if(message==null || message.content==null) return false;
        var optional=TdApiHelp.getFileHandler(message);
        if(optional.isEmpty()) return false;
        if(rule==null) return true;
        FileRecord file=optional.get().convertFileRecord(0);
        if(rule.schemaVersion==0 && (rule.fileTypes==null || rule.fileTypes.isEmpty())
            && !Set.of("photo","video","audio","file").contains(type(message))) return false;
        if(rule.fileTypes!=null && !rule.fileTypes.isEmpty() && !rule.fileTypes.contains("all")
            && !rule.fileTypes.contains(type(message))) return false;
        if(rule.contentTypes!=null && !rule.contentTypes.isEmpty()) {
            String cat=category(file);
            boolean matched = rule.contentTypes.contains(cat);
            if(!matched && rule.contentTypes.contains("file") && Set.of("ebook","archive","file").contains(cat)) {
                matched = true;
            }
            if(!matched && rule.contentTypes.contains("media") && Set.of("photo","video").contains(cat)) {
                matched = true;
            }
            if(!matched) return false;
        }
        if(rule.query!=null && !rule.query.isBlank()) {
            String text=(Objects.toString(file.fileName(),"")+" "+Objects.toString(file.caption(),"")).toLowerCase(Locale.ROOT);
            if(!text.contains(rule.query.strip().toLowerCase(Locale.ROOT))) return false;
        }
        if(rule.fromDate>0 && message.date<rule.fromDate || rule.toDate>0 && message.date>rule.toDate) return false;
        if(!AutoDownloadVerticle.matchMessageSize(message,rule) || !AutoDownloadVerticle.matchExtension(message,rule)) return false;
        return rule.filterExpr==null || rule.filterExpr.isBlank() || MessageFilter.filter(rule.filterExpr).test(message);
    }
    static String type(TdApi.Message message) {
        return switch(message.content) {
            case TdApi.MessagePhoto ignored -> "photo";
            case TdApi.MessageVideo ignored -> "video";
            case TdApi.MessageAudio ignored -> "audio";
            case TdApi.MessageDocument ignored -> "file";
            case TdApi.MessageVoiceNote ignored -> "voice";
            case TdApi.MessageVideoNote ignored -> "video_note";
            case TdApi.MessageAnimation ignored -> "animation";
            case TdApi.MessageSticker ignored -> "sticker";
            default -> "other";
        };
    }
    public static String category(FileRecord file) {
        String name=Objects.toString(file.fileName(),"").toLowerCase(Locale.ROOT);
        String mime=Objects.toString(file.mimeType(),"").toLowerCase(Locale.ROOT);
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1):"";
        if(mime.startsWith("video/") || Set.of("mp4","mkv","mov","webm","avi","m4v","ts").contains(ext) || "video".equals(file.type())) return "video";
        if(mime.startsWith("image/") || Set.of("jpg","jpeg","png","gif","webp","heic","avif").contains(ext) || "photo".equals(file.type())) return "photo";
        if(mime.startsWith("audio/") || Set.of("mp3","flac","m4a","wav","ogg","opus","aac").contains(ext) || "audio".equals(file.type())) return "audio";
        if(Set.of("pdf","epub","mobi","azw","azw3").contains(ext)) return "ebook";
        if(Set.of("zip","rar","7z","tar","gz","bz2","xz").contains(ext)) return "archive";
        return "file";
    }
    public static void validate(SettingAutoRecords.DownloadRule rule) {
        if(rule==null) throw new IllegalArgumentException("Download rule is required");
        if(rule.minSize<0 || rule.maxSize<0 || rule.maxSize>0 && rule.minSize>rule.maxSize)
            throw new IllegalArgumentException("Invalid size range");
        if(rule.fromDate<0 || rule.toDate<0 || rule.toDate>0 && rule.fromDate>rule.toDate)
            throw new IllegalArgumentException("Invalid date range");
        Set<String> types=Set.of("photo","video","audio","file","voice","video_note","animation","sticker","all","media");
        if(rule.fileTypes!=null && !types.containsAll(rule.fileTypes)) throw new IllegalArgumentException("Unsupported message type");
        if(rule.contentTypes!=null && !Set.of("photo","video","audio","ebook","archive","file","media").containsAll(rule.contentTypes))
            throw new IllegalArgumentException("Unsupported content type");
        if(rule.filterExpr!=null && !rule.filterExpr.isBlank()) MessageFilter.getExpression(rule.filterExpr);
    }
}
