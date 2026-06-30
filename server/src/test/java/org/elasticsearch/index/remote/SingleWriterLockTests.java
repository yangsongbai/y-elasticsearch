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
        // First call: readCurrentTerm → NoSuchFileException (lock doesn't exist)
        // Second call: verifyLockHolder → returns written lock content
        when(container.readBlob("lock/ownership.lock"))
            .thenThrow(new NoSuchFileException("not found"))
            .thenReturn(new ByteArrayInputStream("{\"primary_term\":1,\"node_id\":\"node-1\"}".getBytes()));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(1L, "node-1");

        assertTrue(acquired);
        assertTrue(lock.isHeldLocally());
        verify(container).writeBlob(eq("lock/ownership.lock"), any(), anyLong(), eq(true));
    }

    public void testAcquireWithHigherTerm() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        // First call: readCurrentTerm → returns existing lock with lower term
        // Second call: verifyLockHolder → returns our written lock
        when(container.readBlob("lock/ownership.lock"))
            .thenReturn(new ByteArrayInputStream("{\"primary_term\":1,\"node_id\":\"node-1\"}".getBytes()))
            .thenReturn(new ByteArrayInputStream("{\"primary_term\":2,\"node_id\":\"node-2\"}".getBytes()));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(2L, "node-2");

        assertTrue(acquired);
        verify(container).writeBlob(eq("lock/ownership.lock"), any(), anyLong(), eq(true));
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

        // tryAcquire: readCurrentTerm → NoSuchFileException, verifyLockHolder → returns lock
        when(container.readBlob("lock/ownership.lock"))
            .thenThrow(new NoSuchFileException("not found"))
            .thenReturn(new ByteArrayInputStream("{\"primary_term\":1,\"node_id\":\"node-1\"}".getBytes()))
            .thenThrow(new NoSuchFileException("not found"));
        assertTrue(lock.tryAcquire(1L, "node-1"));

        // Simulate renewal failure: write fails
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

        // tryAcquire: readCurrentTerm → NoSuchFileException, verifyLockHolder → returns lock
        when(container.readBlob("lock/ownership.lock"))
            .thenThrow(new NoSuchFileException("not found"))
            .thenReturn(new ByteArrayInputStream("{\"primary_term\":1,\"node_id\":\"node-1\"}".getBytes()))
            .thenThrow(new NoSuchFileException("not found"))
            .thenThrow(new NoSuchFileException("not found"));
        lock.tryAcquire(1L, "node-1");

        // Write fails
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

        // tryAcquire: readCurrentTerm → NoSuchFileException, verifyLockHolder → returns lock
        when(container.readBlob("lock/ownership.lock"))
            .thenThrow(new NoSuchFileException("not found"))
            .thenReturn(new ByteArrayInputStream("{\"primary_term\":1,\"node_id\":\"node-1\"}".getBytes()))
            .thenThrow(new NoSuchFileException("not found"))
            .thenThrow(new NoSuchFileException("not found"));
        lock.tryAcquire(1L, "node-1");

        // Enter degraded mode: write fails
        doThrow(new IOException("timeout"))
            .when(container).writeBlob(anyString(), any(), anyLong(), anyBoolean());
        lock.tryRenew(1L, "node-1");
        lock.tryRenew(1L, "node-1");
        assertTrue(lock.isDegraded());

        // Remote recovers
        reset(container);
        when(container.readBlob("lock/ownership.lock"))
            .thenThrow(new NoSuchFileException("not found"));

        SingleWriterLock.RenewResult result = lock.tryRenew(1L, "node-1");
        assertEquals(SingleWriterLock.RenewResult.SUCCESS, result);
        assertFalse(lock.isDegraded());
    }
}
