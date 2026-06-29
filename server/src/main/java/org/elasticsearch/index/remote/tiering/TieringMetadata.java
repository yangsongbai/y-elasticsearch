/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;

import java.io.IOException;

public class TieringMetadata implements Writeable {

    private final TieringState currentState;
    private final TieringState previousState;
    private final long transitionTimestamp;

    public TieringMetadata(TieringState currentState, TieringState previousState, long transitionTimestamp) {
        this.currentState = currentState;
        this.previousState = previousState;
        this.transitionTimestamp = transitionTimestamp;
    }

    public TieringMetadata(StreamInput in) throws IOException {
        this.currentState = TieringState.valueOf(in.readString());
        this.previousState = TieringState.valueOf(in.readString());
        this.transitionTimestamp = in.readLong();
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
}
