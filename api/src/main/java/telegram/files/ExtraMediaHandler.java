package telegram.files;

import io.vertx.core.json.Json;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;

/** Additional TDLib media keep the existing photo/video/audio/file storage contract. */
final class ExtraMediaHandler extends TdApiHelp.FileHandler<TdApi.MessageContent> {
    ExtraMediaHandler(TdApi.Message message) { super(message); }
    public TdApi.File getFile() {
        return switch(content) {
            case TdApi.MessageVoiceNote m -> m.voiceNote.voice;
            case TdApi.MessageVideoNote m -> m.videoNote.video;
            case TdApi.MessageAnimation m -> m.animation.animation;
            case TdApi.MessageSticker m -> m.sticker.sticker;
            default -> throw new IllegalArgumentException("Unsupported media");
        };
    }
    public Integer getFileId() { return getFile().id; }
    public String getFileUniqueId() { return getFile().remote.uniqueId; }
    @Override public TdApi.Thumbnail getThumbnail() {
        return switch(content) {
            case TdApi.MessageVideoNote m -> m.videoNote.thumbnail;
            case TdApi.MessageAnimation m -> m.animation.thumbnail;
            case TdApi.MessageSticker m -> m.sticker.thumbnail;
            default -> null;
        };
    }
    public FileRecord convertFileRecord(long telegramId) {
        TdApi.File file=getFile();
        String type="file",mime="application/octet-stream",name="",caption="";
        switch(content) {
            case TdApi.MessageVoiceNote m -> { type="audio"; mime=m.voiceNote.mimeType; name="voice_"+message.id+".ogg"; caption=m.caption==null?"":m.caption.text; }
            case TdApi.MessageVideoNote ignored -> { type="video"; mime="video/mp4"; name="video_note_"+message.id+".mp4"; }
            case TdApi.MessageAnimation m -> { type=m.animation.mimeType!=null && m.animation.mimeType.startsWith("video/")?"video":"photo"; mime=m.animation.mimeType; name=m.animation.fileName; caption=m.caption==null?"":m.caption.text; }
            case TdApi.MessageSticker m -> { mime=m.sticker.format instanceof TdApi.StickerFormatWebm?"video/webm":m.sticker.format instanceof TdApi.StickerFormatTgs?"application/x-tgsticker":"image/webp"; name="sticker_"+message.id+(mime.endsWith("webm")?".webm":mime.endsWith("webp")?".webp":".tgs"); }
            default -> { }
        }
        return new FileRecord(file.id,getFileUniqueId(),telegramId,message.chatId,message.id,message.mediaAlbumId,message.date,
            message.restrictionInfo!=null && message.restrictionInfo.hasSensitiveContent,
            file.size>0?file.size:file.expectedSize,file.local==null?0:file.local.downloadedSize,type,mime,name,null,
            getThumbnailFileUniqueId(),caption,Json.encode(getExtraInfo()),null,"idle","idle",System.currentTimeMillis(),null,null,0,0,getReactionCount());
    }
}
