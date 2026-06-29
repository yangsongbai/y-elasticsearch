package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.cache.LFUDecayPolicy;
import org.elasticsearch.index.remote.cache.SharedBlobCacheService;
import org.elasticsearch.index.remote.observability.RemoteStoreTracer;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.mockito.Mockito.*;

public class RemoteCachedDirectoryTests extends ESTestCase {

    public void testOpenInputReadsFromCache() throws IOException {
        BlobContainer blobContainer = mock(BlobContainer.class);
        SharedBlobCacheService cache = new SharedBlobCacheService(
            64 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L),
            new RemoteStoreTracer(false, 0.0));

        byte[] fileContent = "hello world from remote".getBytes();
        when(blobContainer.readBlob(eq("_0.cfs"), anyLong(), anyLong()))
            .thenReturn(new ByteArrayInputStream(fileContent));

        RemoteCachedDirectory dir = new RemoteCachedDirectory(blobContainer, cache, "test-idx/0");
        dir.registerFileLength("_0.cfs", fileContent.length);

        try (IndexInput input = dir.openInput("_0.cfs", IOContext.READ)) {
            byte[] buf = new byte[5];
            input.readBytes(buf, 0, 5);
            assertEquals("hello", new String(buf));
        }
    }

    public void testFileLength() throws IOException {
        BlobContainer blobContainer = mock(BlobContainer.class);
        SharedBlobCacheService cache = new SharedBlobCacheService(
            64 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L),
            new RemoteStoreTracer(false, 0.0));

        RemoteCachedDirectory dir = new RemoteCachedDirectory(blobContainer, cache, "test-idx/0");
        dir.registerFileLength("_0.cfs", 1024L);

        assertEquals(1024L, dir.fileLength("_0.cfs"));
    }
}
