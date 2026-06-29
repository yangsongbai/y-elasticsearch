package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.IndexInput;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.cache.SharedBlobCacheService;

import java.io.IOException;
import java.nio.ByteBuffer;

public class LayeredIndexInput extends IndexInput {

    private final SharedBlobCacheService cache;
    private final BlobContainer blobContainer;
    private final String blobName;
    private final String cacheKey;
    private final long fileLength;
    private long position = 0;

    public LayeredIndexInput(String resourceDescription, SharedBlobCacheService cache,
                             BlobContainer blobContainer, String blobName, String cacheKey, long fileLength) {
        super(resourceDescription);
        this.cache = cache;
        this.blobContainer = blobContainer;
        this.blobName = blobName;
        this.cacheKey = cacheKey;
        this.fileLength = fileLength;
    }

    private LayeredIndexInput(String resourceDescription, SharedBlobCacheService cache,
                              BlobContainer blobContainer, String blobName, String cacheKey,
                              long fileLength, long position) {
        super(resourceDescription);
        this.cache = cache;
        this.blobContainer = blobContainer;
        this.blobName = blobName;
        this.cacheKey = cacheKey;
        this.fileLength = fileLength;
        this.position = position;
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        ByteBuffer data = cache.read(cacheKey, position, len, blobContainer, blobName);
        data.get(b, offset, Math.min(len, data.remaining()));
        position += len;
    }

    @Override
    public byte readByte() throws IOException {
        byte[] buf = new byte[1];
        readBytes(buf, 0, 1);
        return buf[0];
    }

    @Override
    public void close() {}

    @Override
    public long getFilePointer() {
        return position;
    }

    @Override
    public void seek(long pos) {
        this.position = pos;
    }

    @Override
    public long length() {
        return fileLength;
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) {
        return new LayeredIndexInput(sliceDescription, cache, blobContainer, blobName, cacheKey, length, offset);
    }

    @Override
    public IndexInput clone() {
        return new LayeredIndexInput(toString(), cache, blobContainer, blobName, cacheKey, fileLength, position);
    }
}
