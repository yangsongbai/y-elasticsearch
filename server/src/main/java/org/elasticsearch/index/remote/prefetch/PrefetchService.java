package org.elasticsearch.index.remote.prefetch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class PrefetchService {

    private static final Logger logger = LogManager.getLogger(PrefetchService.class);

    private final List<PrefetchPolicy> policies;
    private final ThreadPool threadPool;
    private final Semaphore concurrency;
    private final boolean enabled;
    private final Set<String> inFlight = new HashSet<>();

    public PrefetchService(List<PrefetchPolicy> policies, ThreadPool threadPool,
                           int maxConcurrency, boolean enabled) {
        this.policies = policies;
        this.threadPool = threadPool;
        this.concurrency = new Semaphore(maxConcurrency);
        this.enabled = enabled;
    }

    public void onQueryHit(String hitSegment, List<String> allSegments, Consumer<String> fetchAction) {
        if (!enabled) return;

        for (PrefetchPolicy policy : policies) {
            List<String> targets = policy.selectPrefetchTargets(hitSegment, allSegments);
            for (String target : targets) {
                schedulePrefetch(target, fetchAction);
            }
        }
    }

    public void onShardAllocated(List<String> allFiles, Consumer<String> fetchAction) {
        if (!enabled) return;

        for (PrefetchPolicy policy : policies) {
            List<String> targets = policy.selectPrefetchTargets(null, allFiles);
            for (String target : targets) {
                schedulePrefetch(target, fetchAction);
            }
        }
    }

    private void schedulePrefetch(String target, Consumer<String> fetchAction) {
        synchronized (inFlight) {
            if (inFlight.contains(target)) return;
            inFlight.add(target);
        }

        threadPool.generic().execute(() -> {
            try {
                concurrency.acquire();
                try {
                    fetchAction.accept(target);
                } finally {
                    concurrency.release();
                }
            } catch (Exception e) {
                logger.debug("Prefetch failed for [{}]", target, e);
            } finally {
                synchronized (inFlight) {
                    inFlight.remove(target);
                }
            }
        });
    }
}
