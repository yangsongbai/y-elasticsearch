package org.elasticsearch.index.remote;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.xcontent.DeprecationHandler;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RemoteSegmentMetadata {

    private final long primaryTerm;
    private final long generation;
    private final long checkpoint;
    private final Map<String, FileInfo> files;

    public RemoteSegmentMetadata(long primaryTerm, long generation, long checkpoint, Map<String, FileInfo> files) {
        this.primaryTerm = primaryTerm;
        this.generation = generation;
        this.checkpoint = checkpoint;
        this.files = files;
    }

    public long primaryTerm() {
        return primaryTerm;
    }

    public long generation() {
        return generation;
    }

    public long checkpoint() {
        return checkpoint;
    }

    public Map<String, FileInfo> files() {
        return files;
    }

    public String toFileName() {
        return "metadata__" + primaryTerm + "__" + generation + "__" + checkpoint;
    }

    public static FileNameParts parseFileName(String fileName) {
        String[] parts = fileName.split("__");
        return new FileNameParts(
            Long.parseLong(parts[1]),
            Long.parseLong(parts[2]),
            Long.parseLong(parts[3])
        );
    }

    public BytesReference toXContent() throws IOException {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            builder.startObject();
            builder.field("primary_term", primaryTerm);
            builder.field("generation", generation);
            builder.field("checkpoint", checkpoint);
            builder.startObject("files");
            for (Map.Entry<String, FileInfo> entry : files.entrySet()) {
                builder.startObject(entry.getKey());
                builder.field("size", entry.getValue().size());
                builder.field("checksum", entry.getValue().checksum());
                builder.endObject();
            }
            builder.endObject();
            builder.endObject();
            return BytesReference.bytes(builder);
        }
    }

    public static RemoteSegmentMetadata fromXContent(BytesReference bytes) throws IOException {
        try (XContentParser parser = XContentType.JSON.xContent()
                .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                    BytesReference.toBytes(bytes))) {
            parser.nextToken();
            long primaryTerm = 0;
            long generation = 0;
            long checkpoint = 0;
            Map<String, FileInfo> files = new HashMap<>();
            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("primary_term".equals(field)) {
                    primaryTerm = parser.longValue();
                } else if ("generation".equals(field)) {
                    generation = parser.longValue();
                } else if ("checkpoint".equals(field)) {
                    checkpoint = parser.longValue();
                } else if ("files".equals(field)) {
                    while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                        String fileName = parser.currentName();
                        parser.nextToken();
                        long size = 0;
                        String checksum = "";
                        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                            String f = parser.currentName();
                            parser.nextToken();
                            if ("size".equals(f)) {
                                size = parser.longValue();
                            } else if ("checksum".equals(f)) {
                                checksum = parser.text();
                            }
                        }
                        files.put(fileName, new FileInfo(size, checksum));
                    }
                }
            }
            return new RemoteSegmentMetadata(primaryTerm, generation, checkpoint, files);
        }
    }

    public static class FileInfo {
        private final long size;
        private final String checksum;

        public FileInfo(long size, String checksum) {
            this.size = size;
            this.checksum = checksum;
        }

        public long size() {
            return size;
        }

        public String checksum() {
            return checksum;
        }
    }

    public static class FileNameParts {
        private final long primaryTerm;
        private final long generation;
        private final long checkpoint;

        public FileNameParts(long primaryTerm, long generation, long checkpoint) {
            this.primaryTerm = primaryTerm;
            this.generation = generation;
            this.checkpoint = checkpoint;
        }

        public long primaryTerm() {
            return primaryTerm;
        }

        public long generation() {
            return generation;
        }

        public long checkpoint() {
            return checkpoint;
        }
    }
}
