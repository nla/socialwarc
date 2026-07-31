package org.netpreserve.warcana.common.collection;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaCapturer;
import org.netpreserve.warcana.common.MediaSink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Persists media discovery before download and checkpoints each completed URL.
 */
public class CheckpointingMediaSink implements MediaSink {
    private final CollectionStore store;
    private final CollectionJob job;
    private final WarcWriter writer;
    private final MediaCapturer capturer;

    public CheckpointingMediaSink(
            CollectionStore store,
            CollectionJob job,
            WarcWriter writer,
            String userAgent,
            long maxResourceBytes,
            long maxTotalBytes,
            boolean allVideoVariants,
            org.netpreserve.warcana.common.ProgressReporter progress) {
        this.store = store;
        this.job = job;
        this.writer = writer;
        this.capturer = new MediaCapturer(
                writer,
                userAgent,
                maxResourceBytes,
                maxTotalBytes,
                allVideoVariants,
                progress,
                stats(),
                job.seenMedia,
                this::resourceCompleted);
    }

    @Override
    public void captureAll(Collection<String> urls) {
        discover(urls);
        drain();
    }

    @Override
    public void discover(Collection<String> urls) {
        var known = new LinkedHashSet<>(job.seenMedia);
        known.addAll(job.pendingMedia);
        for (String url : urls) {
            if (url != null && !url.isBlank() && known.add(url)) {
                job.pendingMedia.add(url);
            }
        }
        checkpoint();
    }

    @Override
    public void drain() {
        drainPending();
    }

    public void drainPending() {
        while (!job.pendingMedia.isEmpty()) {
            String url = job.pendingMedia.get(0);
            capturer.captureOne(url);
            job.pendingMedia.remove(0);
            var attempted = new LinkedHashSet<>(job.seenMedia);
            attempted.add(url);
            attempted.addAll(capturer.seenUrls());
            job.seenMedia.clear();
            job.seenMedia.addAll(attempted);
            MediaCaptureStats stats = capturer.stats();
            job.mediaCaptured = stats.captured();
            job.mediaFailed = stats.failed();
            job.mediaSkipped = stats.skipped();
            job.mediaBytes = stats.bytes();
            job.committedOffset = writer.position();
            checkpoint();
        }
    }

    @Override
    public MediaCaptureStats stats() {
        return new MediaCaptureStats(
                job.mediaCaptured,
                job.mediaFailed,
                job.mediaSkipped,
                job.mediaBytes);
    }

    private void checkpoint() {
        try {
            // The API response that disclosed these URLs is now safe to retain.
            job.committedOffset = writer.position();
            store.saveJob(job);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void resourceCompleted(String url) {
        job.seenMedia.add(url);
        MediaCaptureStats stats = capturer.stats();
        job.mediaCaptured = stats.captured();
        job.mediaFailed = stats.failed();
        job.mediaSkipped = stats.skipped();
        job.mediaBytes = stats.bytes();
        job.committedOffset = writer.position();
        checkpoint();
    }
}
