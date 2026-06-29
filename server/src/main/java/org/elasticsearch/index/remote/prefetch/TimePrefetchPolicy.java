package org.elasticsearch.index.remote.prefetch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TimePrefetchPolicy implements PrefetchPolicy {

    private final int lookAheadCount;

    public TimePrefetchPolicy(int lookAheadCount) {
        this.lookAheadCount = lookAheadCount;
    }

    @Override
    public String name() { return "time_adjacent"; }

    @Override
    public List<String> selectPrefetchTargets(String triggerSegment, List<String> availableSegments) {
        int idx = availableSegments.indexOf(triggerSegment);
        if (idx < 0) return Collections.emptyList();

        List<String> targets = new ArrayList<>();
        for (int i = idx + 1; i <= Math.min(idx + lookAheadCount, availableSegments.size() - 1); i++) {
            targets.add(availableSegments.get(i));
        }
        return targets;
    }
}
