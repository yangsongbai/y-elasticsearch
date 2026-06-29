package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class LayeredDirectoryTests extends ESTestCase {

    public void testTailFileRoutesToLocal() throws IOException {
        Directory tailDir = new ByteBuffersDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);

        try (IndexOutput out = tailDir.createOutput("_tail.cfs", IOContext.DEFAULT)) {
            out.writeBytes(new byte[]{1, 2, 3}, 3);
        }

        LayeredDirectory layered = new LayeredDirectory(tailDir, remoteDir);
        layered.markAsTail("_tail.cfs");

        try (IndexInput input = layered.openInput("_tail.cfs", IOContext.READ)) {
            assertEquals(3, input.length());
        }
        verifyNoInteractions(remoteDir);
    }

    public void testNonTailFileRoutesToRemote() throws IOException {
        Directory tailDir = new ByteBuffersDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);
        IndexInput mockInput = mock(IndexInput.class);
        when(remoteDir.openInput("_remote.cfs", IOContext.READ)).thenReturn(mockInput);

        LayeredDirectory layered = new LayeredDirectory(tailDir, remoteDir);

        layered.openInput("_remote.cfs", IOContext.READ);
        verify(remoteDir).openInput("_remote.cfs", IOContext.READ);
    }

    public void testListAllMergesBothSources() throws IOException {
        Directory tailDir = new ByteBuffersDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);

        try (IndexOutput out = tailDir.createOutput("tail.cfs", IOContext.DEFAULT)) {
            out.writeBytes(new byte[]{1}, 1);
        }
        when(remoteDir.listAll()).thenReturn(new String[]{"remote.cfs"});

        LayeredDirectory layered = new LayeredDirectory(tailDir, remoteDir);
        layered.markAsTail("tail.cfs");

        String[] all = layered.listAll();
        assertEquals(2, all.length);
    }
}
