package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;

import static org.mockito.Mockito.*;

public class SingleWriterLockTests extends ESTestCase {

    public void testAcquireFirstTime() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(1L, "node-1");

        assertTrue(acquired);
        assertTrue(lock.isHeldLocally());
        verify(container).writeBlob(eq("lock/ownership.lock"), any(), anyLong(), eq(true));
    }

    public void testAcquireWithHigherTerm() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        String existing = "{\"primary_term\":1,\"node_id\":\"node-1\"}";
        when(container.readBlob("lock/ownership.lock"))
            .thenReturn(new ByteArrayInputStream(existing.getBytes()));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(2L, "node-2");

        assertTrue(acquired);
    }

    public void testAcquireFailsWithLowerTerm() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        String existing = "{\"primary_term\":5,\"node_id\":\"node-1\"}";
        when(container.readBlob("lock/ownership.lock"))
            .thenReturn(new ByteArrayInputStream(existing.getBytes()));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(3L, "node-2");

        assertFalse(acquired);
        assertFalse(lock.isHeldLocally());
    }

    public void testLeaseToleranceAllowsTransientFailure() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        SingleWriterLockConfig config = new SingleWriterLockConfig(30000L, 2, 5000L, true);
        SingleWriterLock lock = new SingleWriterLock(container, config);

        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));
        assertTrue(lock.tryAcquire(1L, "node-1"));

        // Simulate renewal failure
        doThrow(new IOException("connection refused"))
            .when(container).writeBlob(anyString(), any(), anyLong(), anyBoolean());

        SingleWriterLock.RenewResult result1 = lock.tryRenew(1L, "node-1");
        assertEquals(SingleWriterLock.RenewResult.FAILED_TOLERABLE, result1);
        assertTrue(lock.isHeldLocally());
    }

    public void testFastDegradeAfterThreshold() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        SingleWriterLockConfig config = new SingleWriterLockConfig(30000L, 2, 5000L, true);
        SingleWriterLock lock = new SingleWriterLock(container, config);

        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));
        lock.tryAcquire(1L, "node-1");

        doThrow(new IOException("timeout"))
            .when(container).writeBlob(anyString(), any(), anyLong(), anyBoolean());

        lock.tryRenew(1L, "node-1"); // fail 1
        SingleWriterLock.RenewResult result = lock.tryRenew(1L, "node-1"); // fail 2

        assertEquals(SingleWriterLock.RenewResult.DEGRADED, result);
        assertTrue(lock.isDegraded());
        assertTrue(lock.allowWriteInDegradedMode());
    }

    public void testRecoveryFromDegradedMode() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        SingleWriterLockConfig config = new SingleWriterLockConfig(30000L, 2, 5000L, true);
        SingleWriterLock lock = new SingleWriterLock(container, config);

        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));
        lock.tryAcquire(1L, "node-1");

        // Enter degraded mode
        doThrow(new IOException("timeout"))
            .when(container).writeBlob(anyString(), any(), anyLong(), anyBoolean());
        lock.tryRenew(1L, "node-1");
        lock.tryRenew(1L, "node-1");
        assertTrue(lock.isDegraded());

        // Remote recovers
        reset(container);

        SingleWriterLock.RenewResult result = lock.tryRenew(1L, "node-1");
        assertEquals(SingleWriterLock.RenewResult.SUCCESS, result);
        assertFalse(lock.isDegraded());
    }
}
