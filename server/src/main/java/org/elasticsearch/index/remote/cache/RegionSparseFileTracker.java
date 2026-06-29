package org.elasticsearch.index.remote.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class RegionSparseFileTracker {

    public static class Gap {
        private final long start;
        private final long end;

        public Gap(long start, long end) {
            this.start = start;
            this.end = end;
        }

        public long getStart() { return start; }
        public long getEnd() { return end; }
    }

    private final long regionSize;
    private final TreeMap<Long, Long> completedRanges = new TreeMap<>();

    public RegionSparseFileTracker(long regionSize) {
        this.regionSize = regionSize;
    }

    public synchronized void markComplete(long start, long end) {
        Long floorKey = completedRanges.floorKey(start);

        long mergeStart = start;
        long mergeEnd = end;

        if (floorKey != null && completedRanges.get(floorKey) >= start) {
            mergeStart = floorKey;
            mergeEnd = Math.max(mergeEnd, completedRanges.get(floorKey));
            completedRanges.remove(floorKey);
        }

        while (true) {
            Long next = completedRanges.ceilingKey(mergeStart);
            if (next != null && next <= mergeEnd) {
                mergeEnd = Math.max(mergeEnd, completedRanges.get(next));
                completedRanges.remove(next);
            } else {
                break;
            }
        }

        completedRanges.put(mergeStart, mergeEnd);
    }

    public synchronized boolean isRangeAvailable(long start, long end) {
        Long floorKey = completedRanges.floorKey(start);
        if (floorKey == null) return false;
        return completedRanges.get(floorKey) >= end;
    }

    public synchronized List<Gap> getGaps(long start, long end) {
        List<Gap> gaps = new ArrayList<>();
        long pos = start;

        Long floorKey = completedRanges.floorKey(start);
        Long fromKey = (floorKey != null) ? floorKey : start;

        for (Map.Entry<Long, Long> entry : completedRanges.subMap(fromKey, true, end, true).entrySet()) {
            long rangeStart = entry.getKey();
            long rangeEnd = entry.getValue();
            if (rangeStart > pos) {
                gaps.add(new Gap(pos, Math.min(rangeStart, end)));
            }
            pos = Math.max(pos, rangeEnd);
            if (pos >= end) break;
        }
        if (pos < end) {
            gaps.add(new Gap(pos, end));
        }
        return gaps;
    }
}
