package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.cache.SharedBlobCacheService;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteCachedDirectory extends Directory {

    private final BlobContainer blobContainer;
    private final SharedBlobCacheService cache;
    private final String cacheKeyPrefix;
    private final Map<String, Long> fileLengths = new ConcurrentHashMap<>();

    public RemoteCachedDirectory(BlobContainer blobContainer, SharedBlobCacheService cache, String cacheKeyPrefix) {
        this.blobContainer = blobContainer;
        this.cache = cache;
        this.cacheKeyPrefix = cacheKeyPrefix;
    }

    public void registerFileLength(String name, long length) {
        fileLengths.put(name, length);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        long length = fileLengths.getOrDefault(name, 0L);
        String cacheKey = cacheKeyPrefix + "/" + name;
        return new LayeredIndexInput("RemoteCached(" + name + ")", cache, blobContainer, name, cacheKey, length);
    }

    @Override
    public long fileLength(String name) throws IOException {
        return fileLengths.getOrDefault(name, 0L);
    }

    @Override
    public String[] listAll() throws IOException {
        return fileLengths.keySet().toArray(new String[0]);
    }

    @Override
    public void deleteFile(String name) throws IOException {
        fileLengths.remove(name);
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) {
        throw new UnsupportedOperationException("read-only directory");
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) {
        throw new UnsupportedOperationException("read-only directory");
    }

    @Override
    public void sync(Collection<String> names) {}

    @Override
    public void syncMetaData() {}

    @Override
    public void rename(String source, String dest) {
        throw new UnsupportedOperationException("read-only directory");
    }

    @Override
    public Lock obtainLock(String name) {
        return new Lock() {
            @Override
            public void close() {}

            @Override
            public void ensureValid() {}
        };
    }

    @Override
    public void close() {}

    @Override
    public Set<String> getPendingDeletions() {
        return Collections.emptySet();
    }
}
