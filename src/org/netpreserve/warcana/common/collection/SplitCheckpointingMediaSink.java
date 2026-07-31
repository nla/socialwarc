package org.netpreserve.warcana.common.collection;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaCapturer;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.ProgressReporter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Checkpoints non-video media and video to independent WARC streams.
 */
public class SplitCheckpointingMediaSink implements MediaSink, AutoCloseable {
    @FunctionalInterface
    public interface WriterFactory {
        WarcWriter open() throws IOException;
    }

    private final CollectionStore store;
    private final CollectionJob job;
    private final WriterFactory mediaWriterFactory;
    private final WriterFactory videoWriterFactory;
    private final String userAgent;
    private final long maxResourceBytes;
    private final long maxTotalBytes;
    private final boolean allVideoVariants;
    private final ProgressReporter progress;
    private WarcWriter mediaWriter;
    private WarcWriter videoWriter;
    private MediaCapturer mediaCapturer;
    private MediaCapturer videoCapturer;

    public SplitCheckpointingMediaSink(
            CollectionStore store,
            CollectionJob job,
            WriterFactory mediaWriterFactory,
            WriterFactory videoWriterFactory,
            String userAgent,
            long maxResourceBytes,
            long maxTotalBytes,
            boolean allVideoVariants,
            ProgressReporter progress) {
        this.store = store;
        this.job = job;
        this.mediaWriterFactory = mediaWriterFactory;
        this.videoWriterFactory = videoWriterFactory;
        this.userAgent = userAgent;
        this.maxResourceBytes = maxResourceBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.allVideoVariants = allVideoVariants;
        this.progress = progress;
    }

    @Override
    public void captureAll(Collection<String> urls) {
        discover(urls);
        drain();
    }

    @Override
    public void discover(Collection<String> urls) {
        enqueue(urls, job.pendingMedia, job.seenMedia);
    }

    @Override
    public void discoverVideo(Collection<String> urls) {
        enqueue(urls, job.pendingVideo, job.seenVideo);
    }

    private void enqueue(
            Collection<String> urls,
            java.util.List<String> pending,
            java.util.Set<String> seen) {
        var known = new LinkedHashSet<>(seen);
        known.addAll(pending);
        for (String url : urls) {
            if (url != null && !url.isBlank() && known.add(url)) {
                pending.add(url);
            }
        }
        checkpoint();
    }

    @Override
    public void drain() {
        drainMedia();
        drainVideo();
    }

    public void drainPending() {
        drain();
    }

    private void drainMedia() {
        if (job.pendingMedia.isEmpty()) {
            return;
        }
        MediaCapturer capturer = mediaCapturer();
        while (!job.pendingMedia.isEmpty()) {
            String url = job.pendingMedia.get(0);
            capturer.captureOne(url);
            job.pendingMedia.remove(0);
            job.seenMedia.add(url);
            job.seenMedia.addAll(capturer.seenUrls());
            updateMediaStats();
            job.mediaCommittedOffset = mediaWriter.position();
            checkpoint();
        }
    }

    private void drainVideo() {
        if (job.pendingVideo.isEmpty()) {
            return;
        }
        MediaCapturer capturer = videoCapturer();
        while (!job.pendingVideo.isEmpty()) {
            String url = job.pendingVideo.get(0);
            capturer.captureOne(url);
            job.pendingVideo.remove(0);
            job.seenVideo.add(url);
            job.seenVideo.addAll(capturer.seenUrls());
            updateVideoStats();
            job.videoCommittedOffset = videoWriter.position();
            checkpoint();
        }
    }

    private MediaCapturer mediaCapturer() {
        if (mediaCapturer == null) {
            try {
                mediaWriter = mediaWriterFactory.open();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            mediaCapturer = new MediaCapturer(
                    mediaWriter,
                    userAgent,
                    maxResourceBytes,
                    Math.max(1, maxTotalBytes - job.videoBytes),
                    allVideoVariants,
                    progress,
                    new MediaCaptureStats(
                            job.mediaCaptured,
                            job.mediaFailed,
                            job.mediaSkipped,
                            job.mediaBytes),
                    job.seenMedia,
                    this::mediaResourceCompleted);
        }
        return mediaCapturer;
    }

    private MediaCapturer videoCapturer() {
        if (videoCapturer == null) {
            try {
                videoWriter = videoWriterFactory.open();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            videoCapturer = new MediaCapturer(
                    videoWriter,
                    userAgent,
                    maxResourceBytes,
                    Math.max(1, maxTotalBytes - job.mediaBytes),
                    allVideoVariants,
                    progress,
                    new MediaCaptureStats(
                            job.videoCaptured,
                            job.videoFailed,
                            job.videoSkipped,
                            job.videoBytes),
                    job.seenVideo,
                    this::videoResourceCompleted);
        }
        return videoCapturer;
    }

    private void mediaResourceCompleted(String url) {
        job.seenMedia.add(url);
        updateMediaStats();
        job.mediaCommittedOffset = mediaWriter.position();
        checkpoint();
    }

    private void videoResourceCompleted(String url) {
        job.seenVideo.add(url);
        updateVideoStats();
        job.videoCommittedOffset = videoWriter.position();
        checkpoint();
    }

    private void updateMediaStats() {
        MediaCaptureStats stats = mediaCapturer.stats();
        job.mediaCaptured = stats.captured();
        job.mediaFailed = stats.failed();
        job.mediaSkipped = stats.skipped();
        job.mediaBytes = stats.bytes();
    }

    private void updateVideoStats() {
        MediaCaptureStats stats = videoCapturer.stats();
        job.videoCaptured = stats.captured();
        job.videoFailed = stats.failed();
        job.videoSkipped = stats.skipped();
        job.videoBytes = stats.bytes();
    }

    @Override
    public MediaCaptureStats stats() {
        return new MediaCaptureStats(
                job.mediaCaptured + job.videoCaptured,
                job.mediaFailed + job.videoFailed,
                job.mediaSkipped + job.videoSkipped,
                job.mediaBytes + job.videoBytes);
    }

    private void checkpoint() {
        try {
            store.saveJob(job);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (mediaWriter != null) {
            try {
                mediaWriter.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (videoWriter != null) {
            try {
                videoWriter.close();
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
}
