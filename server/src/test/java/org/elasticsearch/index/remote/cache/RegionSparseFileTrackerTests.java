package org.elasticsearch.index.remote.cache;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class RegionSparseFileTrackerTests extends ESTestCase {

    public void testInitiallyEmpty() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        assertFalse(tracker.isRangeAvailable(0, 1024));
    }

    public void testMarkAndCheck() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        tracker.markComplete(0, 4096);
        assertTrue(tracker.isRangeAvailable(0, 4096));
        assertTrue(tracker.isRangeAvailable(100, 500));
        assertFalse(tracker.isRangeAvailable(0, 8192));
    }

    public void testGapDetection() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        tracker.markComplete(0, 1000);
        tracker.markComplete(2000, 3000);

        List<RegionSparseFileTracker.Gap> gaps = tracker.getGaps(0, 3000);
        assertEquals(1, gaps.size());
        assertEquals(1000, gaps.get(0).getStart());
        assertEquals(2000, gaps.get(0).getEnd());
    }

    public void testMergeContiguous() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        tracker.markComplete(0, 1000);
        tracker.markComplete(1000, 2000);
        assertTrue(tracker.isRangeAvailable(0, 2000));
    }
}
