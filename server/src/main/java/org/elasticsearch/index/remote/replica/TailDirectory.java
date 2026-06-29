/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.replica;

import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.FilterDirectory;

/**
 * An in-memory directory for uncommitted tail segments in a Lean Sync Replica.
 * Wraps a {@link ByteBuffersDirectory} and tracks the maximum sequence number stored.
 */
public class TailDirectory extends FilterDirectory {

    private volatile long maxSeqNo = -1;

    public TailDirectory() {
        super(new ByteBuffersDirectory());
    }

    public void updateMaxSeqNo(long seqNo) {
        this.maxSeqNo = Math.max(this.maxSeqNo, seqNo);
    }

    public long getMaxSeqNo() {
        return maxSeqNo;
    }
}
