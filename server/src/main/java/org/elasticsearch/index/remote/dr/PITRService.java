package org.elasticsearch.index.remote.dr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class PITRService {

    private final Map<String, List<PITRMetadata>> recoveryPoints = new ConcurrentHashMap<>();

    public void registerRecoveryPoint(PITRMetadata point) {
        recoveryPoints.computeIfAbsent(point.indexName(), k -> new CopyOnWriteArrayList<>()).add(point);
    }

    public PITRMetadata findClosestRecoveryPoint(String indexName, long targetTimestamp) {
        List<PITRMetadata> points = recoveryPoints.get(indexName);
        if (points == null || points.isEmpty()) return null;

        return points.stream()
            .filter(p -> p.timestamp() <= targetTimestamp)
            .max(Comparator.comparingLong(PITRMetadata::timestamp))
            .orElse(null);
    }

    public List<PITRMetadata> getRecoveryPoints(String indexName) {
        List<PITRMetadata> points = recoveryPoints.get(indexName);
        if (points == null) return Collections.emptyList();
        return points;
    }

    public void pruneRetention(String indexName, long retentionMs) {
        List<PITRMetadata> points = recoveryPoints.get(indexName);
        if (points == null) return;

        long cutoff = System.currentTimeMillis() - retentionMs;
        List<PITRMetadata> snapshot = new ArrayList<>(points);

        List<PITRMetadata> kept = snapshot.stream()
            .filter(p -> p.timestamp() >= cutoff)
            .collect(Collectors.toList());

        Map<Long, PITRMetadata> hourlyBeyond = new HashMap<>();
        for (PITRMetadata p : snapshot) {
            if (p.timestamp() < cutoff) {
                long hourKey = p.timestamp() / 3600_000L;
                PITRMetadata existing = hourlyBeyond.get(hourKey);
                if (existing == null || p.timestamp() > existing.timestamp()) {
                    hourlyBeyond.put(hourKey, p);
                }
            }
        }
        kept.addAll(hourlyBeyond.values());
        kept.sort(Comparator.comparingLong(PITRMetadata::timestamp).reversed());

        recoveryPoints.put(indexName, new CopyOnWriteArrayList<>(kept));
    }
}
