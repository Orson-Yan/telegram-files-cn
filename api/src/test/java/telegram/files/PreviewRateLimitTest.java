package telegram.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreviewRateLimitTest {

    @Test
    void thumbnailsAndChunkedRangesDoNotConsumeThePreviewQuota() {
        assertFalse(HttpVerticle.shouldRateLimitPreview("thumbnail", null));
        assertFalse(HttpVerticle.shouldRateLimitPreview("thumbnail", "bytes=0-"));
        // The TDLib streamer returns 1 MiB per HTTP request, so Range reads belong to one session.
        assertFalse(HttpVerticle.shouldRateLimitPreview("video", "bytes=0-"));
        assertFalse(HttpVerticle.shouldRateLimitPreview("video", "bytes=0-1048575"));
        assertTrue(HttpVerticle.shouldRateLimitPreview("video", null));
        assertTrue(HttpVerticle.shouldRateLimitPreview("video", "   "));
        assertTrue(HttpVerticle.shouldRateLimitPreview("photo", null));
    }

    @Test
    void previewQuotaIsScopedPerClientAndFile() {
        assertEquals("file:10.0.0.5:abc", HttpVerticle.previewRateLimitKey("10.0.0.5", "abc"));
        assertNotEquals(
                HttpVerticle.previewRateLimitKey("10.0.0.5", "abc"),
                HttpVerticle.previewRateLimitKey("10.0.0.5", "def"));
        assertEquals("file:unknown:abc", HttpVerticle.previewRateLimitKey(" ", "abc"));
    }
}
