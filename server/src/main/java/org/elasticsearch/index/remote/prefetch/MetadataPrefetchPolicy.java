package org.elasticsearch.index.remote.prefetch;

import java.util.List;
import java.util.stream.Collectors;

public class MetadataPrefetchPolicy implements PrefetchPolicy {

    @Override
    public String name() { return "metadata"; }

    @Override
    public List<String> selectPrefetchTargets(String triggerSegment, List<String> availableFiles) {
        return availableFiles.stream()
            .filter(f -> f.endsWith(".si") || f.endsWith(".cfe"))
            .collect(Collectors.toList());
    }
}
