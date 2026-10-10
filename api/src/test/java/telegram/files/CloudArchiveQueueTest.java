package telegram.files;

import org.junit.jupiter.api.Test;
import telegram.files.repository.CloudArchiveRecord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudArchiveQueueTest {
    @Test
    void olderRouteHeadWinsOverLiveAndRecoveryEvenWhenInsertedLast() {
        var queue = new AutoCloudArchiveVerticle.AccountQueue();
        queue.add(work(100, 30, null));
        queue.add(work(100, 20, "sync:7:100:200"));
        queue.add(work(100, 10, "history-job"));
        queue.add(work(101, 99, null));
        assertEquals(101, queue.poll().records().getFirst().sourceChatId());
        assertEquals(10, queue.poll().records().getFirst().sourceMessageId());
        assertEquals(20, queue.poll().records().getFirst().sourceMessageId());
        assertEquals(30, queue.poll().records().getFirst().sourceMessageId());
        assertNull(queue.poll());
    }

    private static AutoCloudArchiveVerticle.ArchiveWork work(long source, long id, String job) {
        return new AutoCloudArchiveVerticle.ArchiveWork(List.of(new CloudArchiveRecord(
                source + ":" + id, 7, source, 0, id, 0, 200, 0, null, null,
                "COPY", "MERGE", job, id, "PENDING", 0, 1_000,
                null, null, 1_000, 1_000)));
    }
}
