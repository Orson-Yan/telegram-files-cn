package telegram.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoDownloadVerticleTest {

    @Test
    void keepsPrefetchingUntilTheDownloadBufferIsFull() {
        assertTrue(AutoDownloadVerticle.getScanCapacity(0, 0) > 0);
        assertTrue(AutoDownloadVerticle.getScanCapacity(5, 94) > 0);
        assertEquals(0, AutoDownloadVerticle.getScanCapacity(5, 95));
        assertEquals(0, AutoDownloadVerticle.getScanCapacity(0, 100));
    }

    @Test
    void refillsHistoryBeforeThePrefetchedBufferCanDrain() {
        assertTrue(AutoDownloadVerticle.HISTORY_SCAN_INTERVAL
                   <= (AutoDownloadVerticle.MAX_WAITING_LENGTH
                       / AutoDownloadVerticle.DEFAULT_LIMIT)
                      * AutoDownloadVerticle.DOWNLOAD_INTERVAL);
    }
}
