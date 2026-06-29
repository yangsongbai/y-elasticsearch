package org.elasticsearch.index.remote.cache;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class CacheRegion {

    private final long regionId;
    private final int regionSize;
    private final AtomicLong accessCount = new AtomicLong(0);
    private volatile boolean dirty = false;
    private volatile ByteBuffer buffer;

    public CacheRegion(long regionId, int regionSize) {
        this.regionId = regionId;
        this.regionSize = regionSize;
    }

    public long getRegionId() { return regionId; }
    public int getRegionSize() { return regionSize; }

    public void recordAccess() { accessCount.incrementAndGet(); }
    public long getAccessCount() { return accessCount.get(); }
    public void setAccessCount(long count) { accessCount.set(count); }

    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }

    public void allocate() { buffer = ByteBuffer.allocateDirect(regionSize); }
    public ByteBuffer getBuffer() { return buffer; }
    public void release() { buffer = null; }
}
