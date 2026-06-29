package org.elasticsearch.index.remote.cache;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.observability.RemoteStoreTracer;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.mockito.Mockito.*;

public class SharedBlobCacheServiceTests extends ESTestCase {

    public void testCacheHit() throws IOException {
        SharedBlobCacheService cache = new SharedBlobCacheService(
            64 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L),
            new RemoteStoreTracer(false, 0.0));

        BlobContainer remote = mock(BlobContainer.class);
        byte[] data = randomByteArrayOfLength(4096);
        when(remote.readBlob("file.cfs", 0, 16 * 1024 * 1024))
            .thenReturn(new ByteArrayInputStream(data));

        ByteBuffer result1 = cache.read("idx/shard/file.cfs", 0, 4096, remote, "file.cfs");
        assertNotNull(result1);

        ByteBuffer result2 = cache.read("idx/shard/file.cfs", 0, 4096, remote, "file.cfs");
        assertNotNull(result2);
        verify(remote, times(1)).readBlob("file.cfs", 0, 16 * 1024 * 1024);
    }

    public void testEvictionWhenFull() throws IOException {
        SharedBlobCacheService cache = new SharedBlobCacheService(
            32 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L),
            new RemoteStoreTracer(false, 0.0));

        BlobContainer remote = mock(BlobContainer.class);
        when(remote.readBlob(anyString(), anyLong(), anyLong()))
            .thenReturn(new ByteArrayInputStream(new byte[16 * 1024 * 1024]));

        cache.read("file1", 0, 100, remote, "file1");
        cache.read("file2", 0, 100, remote, "file2");
        cache.read("file3", 0, 100, remote, "file3");

        assertEquals(2, cache.getMaxRegionCount());
    }
}
