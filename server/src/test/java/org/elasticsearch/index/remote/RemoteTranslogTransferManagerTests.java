package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.verify;

public class RemoteTranslogTransferManagerTests extends ESTestCase {

    private ThreadPool threadPool;
    private BlobContainer translogBlobContainer;
    private BlobContainer metadataBlobContainer;
    private RemoteTranslogTransferManager manager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("test");
        translogBlobContainer = mock(BlobContainer.class);
        metadataBlobContainer = mock(BlobContainer.class);
        manager = new RemoteTranslogTransferManager(
            translogBlobContainer, metadataBlobContainer, threadPool, 4
        );
    }

    @Override
    public void tearDown() throws Exception {
        manager.close();
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testUploadTranslogGeneration() throws Exception {
        Path tempDir = createTempDir();
        Path translogFile = tempDir.resolve("translog-1.tlog");
        Files.write(translogFile, "test translog data".getBytes());

        manager.uploadGeneration(translogFile, 1L, 1L, 0L, 100L);

        verify(translogBlobContainer).writeBlob(eq("translog-1.tlog"), any(), anyLong(), eq(true));
    }

    public void testUploadTranslogMetadata() throws Exception {
        manager.uploadTranslogMetadata(1L, 2L, 0L, 500L, 500L);

        verify(metadataBlobContainer).writeBlob(startsWith("translog__1__"), any(), anyLong(), eq(true));
    }
}
