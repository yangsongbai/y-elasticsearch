package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

public class BackpressureControllerTests extends ESTestCase {

    public void testNormalState() {
        BackpressureController controller = new BackpressureController(5, 0.70, 0.90);
        assertEquals(BackpressureController.Level.NORMAL, controller.getLevel());
        assertTrue(controller.allowWrite());
    }

    public void testWarnAfterConsecutiveFailures() {
        BackpressureController controller = new BackpressureController(3, 0.70, 0.90);
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        assertEquals(BackpressureController.Level.WARN, controller.getLevel());
        assertTrue(controller.allowWrite());
    }

    public void testBackpressureOnDiskThreshold() {
        BackpressureController controller = new BackpressureController(5, 0.70, 0.90);
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        controller.updateDiskUsage(0.75);
        assertEquals(BackpressureController.Level.BACKPRESSURE, controller.getLevel());
        assertTrue(controller.allowWrite());
    }

    public void testBlockOnHighDisk() {
        BackpressureController controller = new BackpressureController(5, 0.70, 0.90);
        controller.updateDiskUsage(0.92);
        assertEquals(BackpressureController.Level.BLOCK, controller.getLevel());
        assertFalse(controller.allowWrite());
    }

    public void testRecoveryResetsState() {
        BackpressureController controller = new BackpressureController(3, 0.70, 0.90);
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        assertEquals(BackpressureController.Level.WARN, controller.getLevel());

        controller.recordSuccess();
        assertEquals(BackpressureController.Level.NORMAL, controller.getLevel());
    }
}
