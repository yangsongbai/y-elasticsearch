package org.elasticsearch.index.remote;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RemoteSegmentMetadataTests extends ESTestCase {

    public void testSerializationRoundTrip() throws IOException {
        Map<String, RemoteSegmentMetadata.FileInfo> files = new HashMap<>();
        files.put("_0.cfs", new RemoteSegmentMetadata.FileInfo(134217728L, "sha256:abc123"));
        files.put("_0.si", new RemoteSegmentMetadata.FileInfo(512L, "sha256:def456"));

        RemoteSegmentMetadata original = new RemoteSegmentMetadata(5L, 42L, 9876L, files);

        BytesReference bytes = original.toXContent();
        RemoteSegmentMetadata parsed = RemoteSegmentMetadata.fromXContent(bytes);

        assertEquals(original.primaryTerm(), parsed.primaryTerm());
        assertEquals(original.generation(), parsed.generation());
        assertEquals(original.checkpoint(), parsed.checkpoint());
        assertEquals(original.files().size(), parsed.files().size());
        assertEquals(original.files().get("_0.cfs").size(), parsed.files().get("_0.cfs").size());
        assertEquals(original.files().get("_0.cfs").checksum(), parsed.files().get("_0.cfs").checksum());
    }

    public void testMetadataFileName() {
        Map<String, RemoteSegmentMetadata.FileInfo> files = new HashMap<>();
        RemoteSegmentMetadata metadata = new RemoteSegmentMetadata(5L, 42L, 9876L, files);
        String expected = "metadata__5__42__9876";
        assertEquals(expected, metadata.toFileName());
    }

    public void testParseFileName() {
        RemoteSegmentMetadata.FileNameParts parts = RemoteSegmentMetadata.parseFileName("metadata__5__42__9876");
        assertEquals(5L, parts.primaryTerm());
        assertEquals(42L, parts.generation());
        assertEquals(9876L, parts.checkpoint());
    }
}
