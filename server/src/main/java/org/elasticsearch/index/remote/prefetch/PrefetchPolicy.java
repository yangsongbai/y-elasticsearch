package org.elasticsearch.index.remote.prefetch;

import java.util.List;

public interface PrefetchPolicy {
    String name();
    List<String> selectPrefetchTargets(String triggerSegment, List<String> availableSegments);
}
