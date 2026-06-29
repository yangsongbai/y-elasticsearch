package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobMetadata;
import org.elasticsearch.common.bytes.BytesReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;

public class RemoteSegmentStoreDirectory {

    private final BlobContainer segmentsBlobContainer;
    private final BlobContainer metadataBlobContainer;

    public RemoteSegmentStoreDirectory(BlobContainer segmentsBlobContainer, BlobContainer metadataBlobContainer) {
        this.segmentsBlobContainer = segmentsBlobContainer;
        this.metadataBlobContainer = metadataBlobContainer;
    }

    public void uploadSegmentFile(String fileName, byte[] content) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(content)) {
            segmentsBlobContainer.writeBlob(fileName, stream, content.length, true);
        }
    }

    public void uploadSegmentFile(String fileName, InputStream stream, long length) throws IOException {
        segmentsBlobContainer.writeBlob(fileName, stream, length, true);
    }

    public void uploadMetadata(RemoteSegmentMetadata metadata) throws IOException {
        BytesReference bytes = metadata.toXContent();
        String name = metadata.toFileName();
        try (InputStream stream = bytes.streamInput()) {
            metadataBlobContainer.writeBlob(name, stream, bytes.length(), true);
        }
    }

    public RemoteSegmentMetadata.FileNameParts getLatestMetadataFileName() throws IOException {
        Map<String, BlobMetadata> blobs = metadataBlobContainer.listBlobs();
        return blobs.keySet().stream()
            .filter(name -> name.startsWith("metadata__"))
            .map(RemoteSegmentMetadata::parseFileName)
            .max(Comparator.comparingLong(RemoteSegmentMetadata.FileNameParts::generation))
            .orElse(null);
    }

    public RemoteSegmentMetadata fetchMetadata(String fileName) throws IOException {
        try (InputStream is = metadataBlobContainer.readBlob(fileName)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return RemoteSegmentMetadata.fromXContent(
                BytesReference.fromByteBuffer(java.nio.ByteBuffer.wrap(baos.toByteArray())));
        }
    }

    public InputStream readSegmentFile(String fileName) throws IOException {
        return segmentsBlobContainer.readBlob(fileName);
    }

    public void deleteSegmentFile(String fileName) throws IOException {
        segmentsBlobContainer.deleteBlobsIgnoringIfNotExists(java.util.Collections.singletonList(fileName).iterator());
    }
}
