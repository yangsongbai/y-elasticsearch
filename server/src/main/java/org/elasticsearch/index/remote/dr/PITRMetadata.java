package org.elasticsearch.index.remote.dr;

public class PITRMetadata {

    private final String indexName;
    private final long primaryTerm;
    private final long generation;
    private final long timestamp;

    public PITRMetadata(String indexName, long primaryTerm, long generation, long timestamp) {
        this.indexName = indexName;
        this.primaryTerm = primaryTerm;
        this.generation = generation;
        this.timestamp = timestamp;
    }

    public String indexName() { return indexName; }
    public long primaryTerm() { return primaryTerm; }
    public long generation() { return generation; }
    public long timestamp() { return timestamp; }
}
