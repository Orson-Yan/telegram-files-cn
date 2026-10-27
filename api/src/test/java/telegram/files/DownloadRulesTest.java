package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import telegram.files.repository.SettingAutoRecords;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DownloadRulesTest {
    static TdApi.Message document(String name,String caption) {
        TdApi.File file=new TdApi.File();file.id=42;file.size=2048;
        file.remote=new TdApi.RemoteFile();file.remote.uniqueId="file-42";
        TdApi.Document doc=new TdApi.Document();doc.document=file;doc.fileName=name;doc.mimeType="application/octet-stream";
        TdApi.MessageDocument content=new TdApi.MessageDocument();content.document=doc;content.caption=new TdApi.FormattedText(caption,new TdApi.TextEntity[0]);
        TdApi.Message message=new TdApi.Message();message.chatId=100;message.id=200;message.date=1700000000;message.content=content;
        return message;
    }
    @Test void videoCategoryIncludesDocumentVideoWithoutChangingItsMessageType() {
        var rule=new SettingAutoRecords.DownloadRule();rule.contentTypes=List.of("video");
        assertTrue(DownloadRules.matches(document("movie.MKV",""),rule));
        assertFalse(DownloadRules.matches(document("book.pdf",""),rule));
        rule.fileTypes=List.of("video");
        assertFalse(DownloadRules.matches(document("movie.MKV",""),rule));
        rule.fileTypes=List.of("file");
        assertTrue(DownloadRules.matches(document("movie.MKV",""),rule));
    }
    @Test void liveKeywordMatchesFilenameOrCaptionAndRespectsAllOtherConditions() {
        var rule=new SettingAutoRecords.DownloadRule();rule.query="lesson";
        assertTrue(AutoDownloadVerticle.matchNewMessage(document("LESSON.mp4",""),rule));
        assertTrue(AutoDownloadVerticle.matchNewMessage(document("recording.mp4","Lesson 2"),rule));
        assertFalse(AutoDownloadVerticle.matchNewMessage(document("other.mp4",""),rule));
        rule.deniedExtensions="mp4";
        assertFalse(DownloadRules.matches(document("LESSON.mp4",""),rule));
    }
    @Test void sizeDateAndUnknownTypeAreValidated() {
        var rule=new SettingAutoRecords.DownloadRule();rule.minSize=4096;
        assertFalse(DownloadRules.matches(document("file.zip",""),rule));
        rule.minSize=0;rule.fromDate=1700000001;
        assertFalse(DownloadRules.matches(document("file.zip",""),rule));
        rule.fileTypes=List.of("unsupported");
        assertThrows(IllegalArgumentException.class,() -> DownloadRules.validate(rule));
    }
    @Test void retryWaitAndCrossMidnightScheduleAreExplicit() {
        assertEquals(120000,ChannelDownloadVerticle.retryDelay(new IllegalStateException("FLOOD_WAIT_120"),1));
        assertEquals(5000,ChannelDownloadVerticle.retryDelay(new IllegalStateException("network"),1));
        assertEquals(300000,ChannelDownloadVerticle.retryDelay(new IllegalStateException("network"),8));
        assertTrue(ChannelDownloadVerticle.allowedTime(new telegram.files.repository.SettingTimeLimitedDownload("00:00","00:00")));
        assertFalse(ChannelDownloadVerticle.allowedTime(new telegram.files.repository.SettingTimeLimitedDownload("bad","bad")));
    }
    @Test void sourceReceiptsDoNotCollapseDifferentChannelOccurrences() {
        assertNotEquals(ChannelDownloadStore.jobId(1,10,20,"file"),ChannelDownloadStore.jobId(1,11,20,"file"));
        assertEquals(ChannelDownloadStore.jobId(1,10,20,"file"),ChannelDownloadStore.jobId(1,10,20,"file"));
    }
    @Test void oldEmptyTypeRulesKeepTheirOriginalMediaScope() {
        TdApi.Message message=document("voice.ogg","");
        TdApi.VoiceNote voice=new TdApi.VoiceNote();voice.voice=((TdApi.MessageDocument)message.content).document.document;voice.mimeType="audio/ogg";
        TdApi.MessageVoiceNote content=new TdApi.MessageVoiceNote();content.voiceNote=voice;content.caption=new TdApi.FormattedText("",new TdApi.TextEntity[0]);
        message.content=content;
        var rule=new SettingAutoRecords.DownloadRule();
        assertFalse(DownloadRules.matches(message,rule));
        rule.schemaVersion=1;
        assertTrue(DownloadRules.matches(message,rule));
    }
    @Test void nativeAndSharedDownloadsShareSlotsWithoutDoubleCounting() {
        var nativeFile=TdApiHelp.getFileHandler(document("movie.mp4","")).orElseThrow().convertFileRecord(7);
        var same=new io.vertx.core.json.JsonObject().put("account_id",7).put("status","RUNNING").put("unique_id",nativeFile.uniqueId());
        var seed=new io.vertx.core.json.JsonObject().put("account_id",7).put("status","RUNNING").put("unique_id","seed");
        var other=new io.vertx.core.json.JsonObject().put("account_id",8).put("status","RUNNING").put("unique_id","other");
        var paused=new io.vertx.core.json.JsonObject().put("account_id",7).put("status","PAUSED").put("unique_id","paused");
        assertEquals(2,ChannelDownloadVerticle.occupiedSlots(7,List.of(nativeFile),List.of(same,seed,other,paused)));
    }
}
