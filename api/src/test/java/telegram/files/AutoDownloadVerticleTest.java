package telegram.files;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void reservesExtraBoundedCapacityForRealtimeMessages() {
        assertTrue(AutoDownloadVerticle.MAX_REALTIME_WAITING_LENGTH
                   > AutoDownloadVerticle.MAX_WAITING_LENGTH);
    }

    @Test
    void reservesSlotsWhileDownloadStartsAreInFlight() {
        assertEquals(5, AutoDownloadVerticle.getSurplusSize(5, 0, 0));
        assertEquals(2, AutoDownloadVerticle.getSurplusSize(5, 1, 2));
        assertEquals(0, AutoDownloadVerticle.getSurplusSize(5, 4, 2));
    }

    @Test
    void deduplicatesQueuedAndInFlightFilesAcrossBatches() {
        Set<String> queued = new HashSet<>();
        Set<String> inFlight = new HashSet<>();

        assertTrue(AutoDownloadVerticle.reserveQueueUniqueId("file-a", queued, inFlight));
        assertFalse(AutoDownloadVerticle.reserveQueueUniqueId("file-a", queued, inFlight));

        queued.remove("file-a");
        inFlight.add("file-a");
        assertFalse(AutoDownloadVerticle.reserveQueueUniqueId("file-a", queued, inFlight));
        assertFalse(AutoDownloadVerticle.reserveQueueUniqueId("", queued, inFlight));
    }
}
