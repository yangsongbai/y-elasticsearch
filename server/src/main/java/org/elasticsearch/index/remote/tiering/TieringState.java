/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public enum TieringState {
    CREATING,
    HOT,
    HOT_TO_WARM,
    WARM,
    WARM_TO_COLD,
    COLD,
    ARCHIVED;

    private static final Map<TieringState, Set<TieringState>> VALID_TRANSITIONS;

    static {
        Map<TieringState, Set<TieringState>> map = new HashMap<>();
        map.put(CREATING, EnumSet.of(HOT));
        map.put(HOT, EnumSet.of(HOT_TO_WARM));
        map.put(HOT_TO_WARM, EnumSet.of(WARM, HOT));
        map.put(WARM, EnumSet.of(WARM_TO_COLD, HOT));
        map.put(WARM_TO_COLD, EnumSet.of(COLD, WARM));
        map.put(COLD, EnumSet.of(ARCHIVED, WARM));
        map.put(ARCHIVED, Collections.emptySet());
        VALID_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    public boolean canTransitionTo(TieringState target) {
        Set<TieringState> allowed = VALID_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    public boolean isTransitioning() {
        return this == HOT_TO_WARM || this == WARM_TO_COLD;
    }
}
