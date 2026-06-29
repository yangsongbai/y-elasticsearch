package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobMetadata;
import org.elasticsearch.common.blobstore.support.PlainBlobMetadata;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RemoteSegmentStoreDirectoryTests extends ESTestCase {

    private BlobContainer segmentsBlobContainer;
    private BlobContainer metadataBlobContainer;
    private RemoteSegmentStoreDirectory directory;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        segmentsBlobContainer = mock(BlobContainer.class);
        metadataBlobContainer = mock(BlobContainer.class);
        directory = new RemoteSegmentStoreDirectory(segmentsBlobContainer, metadataBlobContainer);
    }

    public void testUploadSegmentFile() throws IOException {
        byte[] content = "test segment data".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        directory.uploadSegmentFile("_0.cfs", content);

        verify(segmentsBlobContainer).writeBlob(eq("_0.cfs"), any(), eq((long) content.length), eq(true));
    }

    public void testUploadMetadata() throws IOException {
        Map<String, RemoteSegmentMetadata.FileInfo> files = new HashMap<>();
        files.put("_0.cfs", new RemoteSegmentMetadata.FileInfo(1024L, "sha256:abc"));
        RemoteSegmentMetadata metadata = new RemoteSegmentMetadata(1L, 1L, 100L, files);

        directory.uploadMetadata(metadata);

        verify(metadataBlobContainer).writeBlob(eq("metadata__1__1__100"), any(), anyLong(), eq(true));
    }

    public void testGetLatestMetadata() throws IOException {
        Map<String, BlobMetadata> blobs = new HashMap<>();
        blobs.put("metadata__1__1__50", new PlainBlobMetadata("metadata__1__1__50", 100));
        blobs.put("metadata__1__2__100", new PlainBlobMetadata("metadata__1__2__100", 120));
        blobs.put("metadata__1__3__200", new PlainBlobMetadata("metadata__1__3__200", 130));
        when(metadataBlobContainer.listBlobs()).thenReturn(blobs);

        RemoteSegmentMetadata.FileNameParts latest = directory.getLatestMetadataFileName();

        assertEquals(1L, latest.primaryTerm());
        assertEquals(3L, latest.generation());
        assertEquals(200L, latest.checkpoint());
    }
}
