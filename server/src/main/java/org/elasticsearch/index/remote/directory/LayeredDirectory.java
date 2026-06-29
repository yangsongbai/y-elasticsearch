package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public class LayeredDirectory extends Directory {

    private final Directory tailDirectory;
    private final RemoteCachedDirectory remoteDirectory;
    private final Set<String> tailFiles = new HashSet<>();

    public LayeredDirectory(Directory tailDirectory, RemoteCachedDirectory remoteDirectory) {
        this.tailDirectory = tailDirectory;
        this.remoteDirectory = remoteDirectory;
    }

    public void markAsTail(String fileName) {
        tailFiles.add(fileName);
    }

    public void unmarkTail(String fileName) {
        tailFiles.remove(fileName);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        if (tailFiles.contains(name)) {
            return tailDirectory.openInput(name, context);
        }
        return remoteDirectory.openInput(name, context);
    }

    @Override
    public String[] listAll() throws IOException {
        Set<String> all = new TreeSet<>();
        for (String f : tailDirectory.listAll()) {
            if (tailFiles.contains(f)) {
                all.add(f);
            }
        }
        for (String f : remoteDirectory.listAll()) {
            all.add(f);
        }
        return all.toArray(new String[0]);
    }

    @Override
    public long fileLength(String name) throws IOException {
        if (tailFiles.contains(name)) {
            return tailDirectory.fileLength(name);
        }
        return remoteDirectory.fileLength(name);
    }

    @Override
    public void deleteFile(String name) throws IOException {
        if (tailFiles.contains(name)) {
            tailDirectory.deleteFile(name);
            tailFiles.remove(name);
        } else {
            remoteDirectory.deleteFile(name);
        }
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) throws IOException {
        tailFiles.add(name);
        return tailDirectory.createOutput(name, context);
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
        return tailDirectory.createTempOutput(prefix, suffix, context);
    }

    @Override
    public void sync(Collection<String> names) throws IOException {
        tailDirectory.sync(names);
    }

    @Override
    public void syncMetaData() throws IOException {
        tailDirectory.syncMetaData();
    }

    @Override
    public void rename(String source, String dest) throws IOException {
        tailDirectory.rename(source, dest);
    }

    @Override
    public Lock obtainLock(String name) throws IOException {
        return tailDirectory.obtainLock(name);
    }

    @Override
    public void close() throws IOException {
        tailDirectory.close();
        remoteDirectory.close();
    }

    @Override
    public Set<String> getPendingDeletions() throws IOException {
        return tailDirectory.getPendingDeletions();
    }
}
