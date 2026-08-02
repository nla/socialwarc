package org.netpreserve.warcana;

import org.netpreserve.jwarc.FetchOptions;
import org.netpreserve.jwarc.FetchResult;
import org.netpreserve.jwarc.HttpRequest;
import org.netpreserve.jwarc.WarcCompression;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.collection.CollectionState;
import org.netpreserve.warcana.common.collection.CollectionStore;
import org.netpreserve.warcana.common.collection.CollectionWarcStream;
import org.netpreserve.warcana.common.collection.WarcStreamState;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the three shared WARC streams for one collection run. Network fetches
 * happen concurrently into temporary files; completed exchanges are appended
 * serially to the shared streams.
 */
final class CollectionRunWriters implements AutoCloseable {
    private static final long MAX_WARC_BYTES = 1024L * 1024 * 1024;
    private final CollectionStore store;
    private final ArchiveOptions options;
    private final ProgressReporter progress;
    private final HostRequestScheduler scheduler = new HostRequestScheduler();
    private final CollectionState state;
    private final Map<CollectionWarcStream, StreamWriter> streams =
            new java.util.EnumMap<>(CollectionWarcStream.class);
    private boolean closed;

    CollectionRunWriters(
            CollectionStore store,
            ArchiveOptions options,
            ProgressReporter progress) throws IOException {
        this.store = store;
        this.options = options;
        this.progress = progress;
        state = store.loadCollectionState();
    }

    CollectionState state() {
        return state;
    }

    synchronized WarcWriter lease(CollectionWarcStream stream)
            throws IOException {
        if (closed) {
            throw new IOException("Collection run writer is closed");
        }
        StreamWriter writer = streams.get(stream);
        if (writer == null) {
            writer = new StreamWriter(stream);
            streams.put(stream, writer);
        }
        return new Lease(writer);
    }

    synchronized List<Path> finalizeWarcs() throws IOException {
        var completed = new ArrayList<Path>();
        IOException failure = null;
        for (StreamWriter writer : streams.values()) {
            try {
                Path path = writer.finalizeWarc();
                if (path != null) {
                    completed.add(path);
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return List.copyOf(completed);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        for (StreamWriter writer : streams.values()) {
            try {
                writer.closeDelegate();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private final class StreamWriter {
        private final CollectionWarcStream stream;
        private WarcWriter delegate;
        private long delegateBaseOffset;

        StreamWriter(CollectionWarcStream stream) throws IOException {
            this.stream = stream;
            synchronized (state) {
                if (stream.from(state).openWarc() == null) {
                    createPaths();
                    store.saveCollectionState(state);
                }
            }
            openAtCheckpoint();
            if (position() == 0) {
                delegate.write(warcinfo());
                commit();
            }
        }

        FetchResult fetch(
                URI uri, HttpRequest request, FetchOptions options)
                throws IOException {
            return scheduler.execute(uri, () -> {
                Path temporary = Files.createTempFile(
                        store.jobsDirectory(), "capture-", ".warc");
                try {
                    FetchResult result;
                    try (var staging = new WarcWriter(temporary)) {
                        if (request == null) {
                            result = options == null
                                    ? staging.fetch(uri)
                                    : staging.fetch(uri, options);
                        } else {
                            result = staging.fetch(uri, request, options);
                        }
                    }
                    append(temporary);
                    return result;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            });
        }

        FetchResult fetch(
                URI uri, HttpRequest request, OutputStream copyTo)
                throws IOException {
            return scheduler.execute(uri, () -> {
                Path temporary = Files.createTempFile(
                        store.jobsDirectory(), "capture-", ".warc");
                try {
                    FetchResult result;
                    try (var staging = new WarcWriter(temporary)) {
                        result = staging.fetch(uri, request, copyTo);
                    }
                    append(temporary);
                    return result;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            });
        }

        private synchronized void append(Path temporary) throws IOException {
            rollIfNeeded();
            try (var reader = new WarcReader(temporary)) {
                while (true) {
                    var next = reader.next();
                    if (next.isEmpty()) {
                        break;
                    }
                    delegate.write(next.get());
                }
            }
            commit();
        }

        synchronized void write(WarcRecord record) throws IOException {
            rollIfNeeded();
            delegate.write(record);
            commit();
        }

        private void rollIfNeeded() throws IOException {
            if (position() < MAX_WARC_BYTES) {
                return;
            }
            Path completed = finalizeWarc();
            progress.report("Collection WARC reached 1 GiB: " +
                    completed.getFileName());
            synchronized (state) {
                createPaths();
                store.saveCollectionState(state);
            }
            openAtCheckpoint();
            delegate.write(warcinfo());
            commit();
        }

        private void createPaths() {
            String basename = stream.filenamePrefix() + timestamp() + "-" +
                    UUID.randomUUID().toString().substring(0, 8) +
                    (stream == CollectionWarcStream.POSTS
                            ? ".warc.gz"
                            : ".warc");
            WarcStreamState streamState = stream.from(state);
            streamState.setPaths(
                    "warcs/" + basename + ".open",
                    "warcs/" + basename);
            streamState.setCommittedOffset(0);
        }

        private void openAtCheckpoint() throws IOException {
            WarcStreamState checkpoint = stream.from(state);
            Path open = store.root().resolve(checkpoint.openWarc());
            Files.createDirectories(open.getParent());
            var channel = FileChannel.open(
                    open, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            if (channel.size() < checkpoint.committedOffset()) {
                channel.close();
                throw new IOException(
                        "Open WARC is shorter than its checkpoint: " + open);
            }
            channel.truncate(checkpoint.committedOffset());
            channel.position(checkpoint.committedOffset());
            WarcCompression compression = checkpoint.finalWarc().endsWith(".gz")
                    ? WarcCompression.GZIP
                    : WarcCompression.NONE;
            if (compression == WarcCompression.GZIP) {
                delegateBaseOffset = checkpoint.committedOffset();
                delegate = new WarcWriter(
                        new RelativeWritableChannel(channel), compression);
            } else {
                delegateBaseOffset = 0;
                delegate = new WarcWriter(channel, compression);
            }
        }

        private synchronized Path finalizeWarc() throws IOException {
            WarcStreamState streamState = stream.from(state);
            if (streamState.openWarc() == null) {
                return null;
            }
            commit();
            closeDelegate();
            Path open = store.root().resolve(streamState.openWarc());
            Path completed = store.root().resolve(streamState.finalWarc());
            moveCompleted(open, completed);
            store.log("WARC-COMPLETE " + completed.getFileName());
            synchronized (state) {
                streamState.clear();
                store.saveCollectionState(state);
            }
            return completed;
        }

        private void commit() throws IOException {
            synchronized (state) {
                stream.from(state).setCommittedOffset(position());
                store.saveCollectionState(state);
            }
        }

        private long position() {
            return delegate == null
                    ? 0
                    : delegateBaseOffset + delegate.position();
        }

        private void closeDelegate() throws IOException {
            if (delegate != null) {
                delegate.close();
                delegate = null;
            }
        }

        private Warcinfo warcinfo() {
            WarcStreamState streamState = stream.from(state);
            Map<String, List<String>> metadata = new LinkedHashMap<>();
            metadata.put("software", List.of("warcana/0.1.0"));
            metadata.put("user-agent", List.of(options.userAgent()));
            metadata.put("scope", List.of("collection-run"));
            metadata.put("collection", List.of(store.root().toString()));
            metadata.put("created", List.of(Instant.now().toString()));
            metadata.put("stream", List.of(
                    stream.name().toLowerCase(java.util.Locale.ROOT)));
            return new Warcinfo.Builder()
                    .filename(Path.of(streamState.finalWarc())
                            .getFileName().toString())
                    .fields(metadata)
                    .build();
        }
    }

    private final class Lease extends WarcWriter {
        private final StreamWriter stream;

        Lease(StreamWriter stream) throws IOException {
            super(OutputStream.nullOutputStream());
            this.stream = stream;
        }

        @Override
        public void write(WarcRecord record) throws IOException {
            stream.write(record);
        }

        @Override
        public FetchResult fetch(URI uri) throws IOException {
            return stream.fetch(uri, null, (FetchOptions) null);
        }

        @Override
        public FetchResult fetch(URI uri, FetchOptions options)
                throws IOException {
            return stream.fetch(uri, null, options);
        }

        @Override
        public FetchResult fetch(
                URI uri, HttpRequest request, OutputStream copyTo)
                throws IOException {
            return stream.fetch(uri, request, copyTo);
        }

        @Override
        public FetchResult fetch(
                URI uri, HttpRequest request, FetchOptions options)
                throws IOException {
            return stream.fetch(uri, request, options);
        }

        @Override
        public long position() {
            return stream.position();
        }

        @Override
        public void close() throws IOException {
            super.close();
        }
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
    }

    private static void moveCompleted(Path open, Path completed)
            throws IOException {
        try {
            Files.move(open, completed, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(open, completed);
        }
    }

    private record RelativeWritableChannel(WritableByteChannel delegate)
            implements WritableByteChannel {
        @Override
        public int write(java.nio.ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
