package org.netpreserve.warcana;

import org.netpreserve.jwarc.FetchOptions;
import org.netpreserve.jwarc.FetchResult;
import org.netpreserve.jwarc.HttpRequest;
import org.netpreserve.jwarc.WarcCompression;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.collection.CollectionJob;
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
import java.util.UUID;
import java.util.function.Function;

/**
 * Keeps API clients and media capture on one logical writer while rolling
 * a collection WARC at complete HTTP exchange boundaries.
 */
final class RollingCollectionWarcWriter extends WarcWriter {
    static final long MAX_WARC_BYTES = 1024L * 1024 * 1024;

    private final CollectionStore store;
    private final CollectionState state;
    private final CollectionJob job;
    private final CollectionWarcStream stream;
    private final Function<String, Warcinfo> warcinfoFactory;
    private final ProgressReporter progress;
    private WarcWriter delegate;
    private long delegateBaseOffset;
    private boolean closed;

    RollingCollectionWarcWriter(
            CollectionStore store,
            CollectionState state,
            CollectionJob job,
            CollectionWarcStream stream,
            Function<String, Warcinfo> warcinfoFactory,
            ProgressReporter progress) throws IOException {
        super(OutputStream.nullOutputStream());
        this.store = store;
        this.state = state;
        this.job = job;
        this.stream = stream;
        this.warcinfoFactory = warcinfoFactory;
        this.progress = progress;
        boolean created = attachOrCreateStream();
        openAtCheckpoint();
        if (created && stream != CollectionWarcStream.POSTS) {
            delegate.write(warcinfoFactory.apply(
                    Path.of(jobStream().finalWarc())
                            .getFileName().toString()));
            setCommittedOffset(position());
            store.saveJob(job);
            store.saveCollectionState(state);
        }
    }

    private boolean attachOrCreateStream() throws IOException {
        if (jobStream().openWarc() != null) {
            return false;
        }
        if (stream.from(state).openWarc() != null) {
            stream.copyToJob(state, job);
            store.saveJob(job);
            return false;
        }
        createStreamPaths();
        store.saveCollectionState(state);
        store.saveJob(job);
        return true;
    }

    private void createStreamPaths() {
        String basename = stream.filenamePrefix() + timestamp() + "-" +
                UUID.randomUUID().toString().substring(0, 8) +
                (stream == CollectionWarcStream.POSTS
                        ? ".warc.gz"
                        : ".warc");
        WarcStreamState created = jobStream();
        created.setPaths(
                "warcs/" + basename + ".open",
                "warcs/" + basename);
        created.setCommittedOffset(0);
        stream.copyToState(job, state);
    }

    private void openAtCheckpoint() throws IOException {
        WarcStreamState checkpoint = jobStream();
        Path open = store.root().resolve(checkpoint.openWarc());
        Files.createDirectories(open.getParent());
        var channel = FileChannel.open(
                open,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
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

    private void rollIfNeeded() throws IOException {
        if (position() < MAX_WARC_BYTES) {
            return;
        }
        setCommittedOffset(position());
        store.saveJob(job);
        store.saveCollectionState(state);
        delegate.close();

        WarcStreamState checkpoint = jobStream();
        Path completed = store.root().resolve(checkpoint.finalWarc());
        Path open = store.root().resolve(checkpoint.openWarc());
        moveCompleted(open, completed);
        store.log("WARC-ROLLOVER " + completed.getFileName());
        progress.report(
                "Collection WARC reached 1 GiB; starting a new WARC: " +
                        completed.getFileName());

        createStreamPaths();
        store.saveCollectionState(state);
        store.saveJob(job);
        openAtCheckpoint();

        delegate.write(warcinfoFactory.apply(
                Path.of(jobStream().finalWarc())
                        .getFileName().toString()));
        setCommittedOffset(position());
        store.saveJob(job);
        store.saveCollectionState(state);
    }

    static void moveCompleted(Path open, Path completed) throws IOException {
        try {
            Files.move(open, completed, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(open, completed);
        }
    }

    private static String timestamp() {
        return DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    @Override
    public synchronized void write(WarcRecord record) throws IOException {
        delegate.write(record);
    }

    @Override
    public FetchResult fetch(URI uri) throws IOException {
        rollIfNeeded();
        return delegate.fetch(uri);
    }

    @Override
    public FetchResult fetch(
            URI uri, FetchOptions options) throws IOException {
        rollIfNeeded();
        return delegate.fetch(uri, options);
    }

    @Override
    public FetchResult fetch(
            URI uri, HttpRequest request, OutputStream copyTo)
            throws IOException {
        rollIfNeeded();
        return delegate.fetch(uri, request, copyTo);
    }

    @Override
    public FetchResult fetch(
            URI uri, HttpRequest request, FetchOptions options)
            throws IOException {
        rollIfNeeded();
        return delegate.fetch(uri, request, options);
    }

    @Override
    public long position() {
        return delegate == null ? 0 :
                delegateBaseOffset + delegate.position();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (delegate != null) {
                delegate.close();
            }
            super.close();
        }
    }

    private WarcStreamState jobStream() {
        return stream.from(job);
    }

    private void setCommittedOffset(long offset) {
        jobStream().setCommittedOffset(offset);
        stream.copyToState(job, state);
    }

    /**
     * Hides the seekable channel's absolute position from WarcWriter so a
     * reopened gzip writer reports bytes relative to this append session.
     */
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
