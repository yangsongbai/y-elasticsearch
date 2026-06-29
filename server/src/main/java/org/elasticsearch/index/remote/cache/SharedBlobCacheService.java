package org.elasticsearch.index.remote.cache;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.observability.RemoteStoreTracer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SharedBlobCacheService {

    private static final Logger logger = LogManager.getLogger(SharedBlobCacheService.class);

    private final long totalSize;
    private final int regionSize;
    private final int maxRegions;
    private final LFUDecayPolicy evictionPolicy;
    private final Map<String, CacheRegion> regionMap = new ConcurrentHashMap<>();
    private final Map<String, RegionSparseFileTracker> trackers = new ConcurrentHashMap<>();
    private final RemoteStoreTracer tracer;

    public SharedBlobCacheService(long totalSize, int regionSize, LFUDecayPolicy evictionPolicy, RemoteStoreTracer tracer) {
        this.totalSize = totalSize;
        this.regionSize = regionSize;
        this.maxRegions = (int) (totalSize / regionSize);
        this.evictionPolicy = evictionPolicy;
        this.tracer = tracer;
    }

    public ByteBuffer read(String cacheKey, long position, int length,
                           BlobContainer remote, String blobName) throws IOException {
        String regionKey = cacheKey + ":" + (position / regionSize);
        RegionSparseFileTracker tracker = trackers.computeIfAbsent(
            regionKey, k -> new RegionSparseFileTracker(regionSize));

        long regionOffset = (position / regionSize) * (long) regionSize;
        long offsetInRegion = position - regionOffset;

        CacheRegion region = regionMap.get(regionKey);
        if (region != null && tracker.isRangeAvailable(offsetInRegion, offsetInRegion + length)) {
            region.recordAccess();
            ByteBuffer buf = region.getBuffer().duplicate();
            buf.position((int) offsetInRegion);
            buf.limit((int) (offsetInRegion + length));
            return buf.slice();
        }

        if (region == null) {
            ensureCapacity();
            region = new CacheRegion(regionMap.size(), regionSize);
            region.allocate();
            regionMap.put(regionKey, region);
        }

        RemoteStoreTracer.SpanHandle span = tracer.startSpan("cache_miss_fetch", regionKey);
        try (InputStream is = remote.readBlob(blobName, regionOffset, regionSize)) {
            byte[] data = readFully(is);
            region.getBuffer().clear();
            region.getBuffer().put(data, 0, Math.min(data.length, regionSize));
            tracker.markComplete(0, Math.min(data.length, regionSize));
            span.addAttribute("bytes_fetched", (long) data.length);
            span.end(true);
        } catch (IOException e) {
            span.end(false);
            throw e;
        }

        region.recordAccess();
        ByteBuffer buf = region.getBuffer().duplicate();
        buf.position((int) offsetInRegion);
        buf.limit((int) Math.min(offsetInRegion + length, region.getBuffer().capacity()));
        return buf.slice();
    }

    private void ensureCapacity() {
        if (regionMap.size() >= maxRegions) {
            List<CacheRegion> candidates = new ArrayList<>(regionMap.values());
            CacheRegion victim = evictionPolicy.selectVictim(candidates);
            if (victim != null) {
                String victimKey = null;
                for (Map.Entry<String, CacheRegion> entry : regionMap.entrySet()) {
                    if (entry.getValue() == victim) {
                        victimKey = entry.getKey();
                        break;
                    }
                }
                if (victimKey != null) {
                    regionMap.remove(victimKey);
                    trackers.remove(victimKey);
                    victim.release();
                }
            }
        }
    }

    public int getMaxRegionCount() { return maxRegions; }
    public long getTotalSize() { return totalSize; }

    private static byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buf)) != -1) {
            baos.write(buf, 0, bytesRead);
        }
        return baos.toByteArray();
    }
}
