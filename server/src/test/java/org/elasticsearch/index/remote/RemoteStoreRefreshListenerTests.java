package org.elasticsearch.index.remote;

import org.apache.lucene.store.Directory;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.test.ESTestCase;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class RemoteStoreRefreshListenerTests extends ESTestCase {

    public void testAfterRefreshTriggersUpload() {
        Store store = mock(Store.class);
        Directory localDir = mock(Directory.class);
        SegmentUploadScheduler scheduler = mock(SegmentUploadScheduler.class);
        RemoteSegmentStoreDirectory remoteDir = mock(RemoteSegmentStoreDirectory.class);
        ShardId shardId = new ShardId("test-index", "uuid", 0);

        RemoteStoreRefreshListener listener = new RemoteStoreRefreshListener(
            shardId, store, localDir, remoteDir, scheduler, 1L
        );

        listener.afterRefresh(true);
    }

    public void testAfterRefreshSkipsWhenDidNotRefresh() {
        Store store = mock(Store.class);
        Directory localDir = mock(Directory.class);
        SegmentUploadScheduler scheduler = mock(SegmentUploadScheduler.class);
        RemoteSegmentStoreDirectory remoteDir = mock(RemoteSegmentStoreDirectory.class);
        ShardId shardId = new ShardId("test-index", "uuid", 0);

        RemoteStoreRefreshListener listener = new RemoteStoreRefreshListener(
            shardId, store, localDir, remoteDir, scheduler, 1L
        );

        listener.afterRefresh(false);

        verifyNoInteractions(scheduler);
    }
}
