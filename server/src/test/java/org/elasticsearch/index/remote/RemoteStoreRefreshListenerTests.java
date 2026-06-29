package org.elasticsearch.index.remote;

import org.apache.lucene.store.Directory;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class RemoteStoreRefreshListenerTests extends ESTestCase {

    public void testAfterRefreshTriggersUpload() throws Exception {
        Store store = mock(Store.class);
        Directory localDir = mock(Directory.class);
        SegmentUploadScheduler scheduler = mock(SegmentUploadScheduler.class);
        RemoteSegmentStoreDirectory remoteDir = mock(RemoteSegmentStoreDirectory.class);
        ShardId shardId = new ShardId("test-index", "uuid", 0);
        ThreadPool threadPool = new TestThreadPool("test");

        try {
            RemoteStoreRefreshListener listener = new RemoteStoreRefreshListener(
                shardId, store, localDir, remoteDir, scheduler, 1L, threadPool
            );

            listener.afterRefresh(true);
        } finally {
            terminate(threadPool);
        }
    }

    public void testAfterRefreshSkipsWhenDidNotRefresh() throws Exception {
        Store store = mock(Store.class);
        Directory localDir = mock(Directory.class);
        SegmentUploadScheduler scheduler = mock(SegmentUploadScheduler.class);
        RemoteSegmentStoreDirectory remoteDir = mock(RemoteSegmentStoreDirectory.class);
        ShardId shardId = new ShardId("test-index", "uuid", 0);
        ThreadPool threadPool = new TestThreadPool("test");

        try {
            RemoteStoreRefreshListener listener = new RemoteStoreRefreshListener(
                shardId, store, localDir, remoteDir, scheduler, 1L, threadPool
            );

            listener.afterRefresh(false);

            verifyNoInteractions(scheduler);
        } finally {
            terminate(threadPool);
        }
    }

    public void testInactiveListenerSkipsUpload() {
        Store store = mock(Store.class);
        Directory localDir = mock(Directory.class);
        ShardId shardId = new ShardId("test-index", "uuid", 0);

        RemoteStoreRefreshListener listener = new RemoteStoreRefreshListener(
            shardId, store, localDir
        );

        assertFalse(listener.isActive());
        listener.afterRefresh(true);
        verifyNoInteractions(store);
    }
}
