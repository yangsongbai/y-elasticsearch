/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;

import java.io.IOException;

public class TieringMetadata implements Writeable {

    private static final Logger logger = LogManager.getLogger(TieringMetadata.class);

    private final TieringState currentState;
    private final TieringState previousState;
    private final long transitionTimestamp;
    private final boolean hasUnknownState;

    public TieringMetadata(TieringState currentState, TieringState previousState, long transitionTimestamp) {
        this(currentState, previousState, transitionTimestamp, false);
    }

    public TieringMetadata(TieringState currentState, TieringState previousState, long transitionTimestamp,
                           boolean hasUnknownState) {
        this.currentState = currentState;
        this.previousState = previousState;
        this.transitionTimestamp = transitionTimestamp;
        this.hasUnknownState = hasUnknownState;
    }

    public TieringMetadata(StreamInput in) throws IOException {
        ParsedState current = readStateSafe(in);
        ParsedState previous = readStateSafe(in);
        this.currentState = current.state;
        this.previousState = previous.state;
        this.transitionTimestamp = in.readLong();
        this.hasUnknownState = current.unknown || previous.unknown;
    }

    private static ParsedState readStateSafe(StreamInput in) throws IOException {
        String name = in.readString();
        try {
            return new ParsedState(TieringState.valueOf(name), false);
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown TieringState [{}], falling back to HOT. " +
                "Transitions will be blocked until cluster is fully upgraded.", name);
            return new ParsedState(TieringState.HOT, true);
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(currentState.name());
        out.writeString(previousState.name());
        out.writeLong(transitionTimestamp);
    }

    public TieringState getCurrentState() {
        return currentState;
    }

    public TieringState getPreviousState() {
        return previousState;
    }

    public long getTransitionTimestamp() {
        return transitionTimestamp;
    }

    public boolean hasUnknownState() {
        return hasUnknownState;
    }

    private static class ParsedState {
        final TieringState state;
        final boolean unknown;

        ParsedState(TieringState state, boolean unknown) {
            this.state = state;
            this.unknown = unknown;
        }
    }
}
