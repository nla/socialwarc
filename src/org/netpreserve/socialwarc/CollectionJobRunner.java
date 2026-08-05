package org.netpreserve.socialwarc;

import org.netpreserve.socialwarc.bluesky.AtProtocolClient;
import org.netpreserve.socialwarc.bluesky.BlueskyAccountArchiver;
import org.netpreserve.socialwarc.bluesky.BlueskyAccountReference;
import org.netpreserve.socialwarc.common.ArchiveCheckpoint;
import org.netpreserve.socialwarc.common.ArchiveRunOptions;
import org.netpreserve.socialwarc.common.MediaCaptureStats;
import org.netpreserve.socialwarc.common.ProgressReporter;
import org.netpreserve.socialwarc.common.collection.CollectionJob;
import org.netpreserve.socialwarc.common.collection.CollectionState;
import org.netpreserve.socialwarc.common.collection.CollectionStore;
import org.netpreserve.socialwarc.common.collection.CollectionWarcStream;
import org.netpreserve.socialwarc.common.collection.SplitCheckpointingMediaSink;
import org.netpreserve.socialwarc.mastodon.MastodonAccountArchiver;
import org.netpreserve.socialwarc.mastodon.MastodonAccountReference;
import org.netpreserve.socialwarc.mastodon.MastodonLocalTimelineArchiver;
import org.netpreserve.socialwarc.mastodon.MastodonServerReference;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;

final class CollectionJobRunner {
    private final CollectionStore store;
    private final ArchiveOptions options;
    private final PrintStream out;
    private final ProgressReporter progress;
    private final CollectionRunWriters runWriters;
    private final CollectionState collectionState;
    private final ArchiveCommand.ProgressListener progressListener;

    CollectionJobRunner(
            CollectionStore store,
            ArchiveOptions options,
            PrintStream out,
            ProgressReporter progress,
            CollectionRunWriters runWriters,
            ArchiveCommand.ProgressListener progressListener) {
        this.store = store;
        this.options = options;
        this.out = out;
        this.progress = progress;
        this.runWriters = runWriters;
        this.progressListener = progressListener;
        collectionState = runWriters.state();
    }

    void run(
            String accountArgument,
            ArchivePlatform platform,
            boolean localTimeline)
            throws IOException {
        String platformName = platform.externalName();
        String targetType = localTimeline ? "mastodon-local" : platformName;
        String key = CollectionStore.accountKey(
                targetType, accountArgument);
        CollectionState.AccountProgress previous;
        synchronized (collectionState) {
            previous = collectionState.accounts.get(key);
        }
        CollectionJob job = store.loadJob(key);

        boolean resumed = job != null;
        if (job == null) {
            job = createJob(
                    key, accountArgument, platform, localTimeline,
                    previous);
        } else {
            resumeJob(accountArgument, job);
        }
        progressListener.targetUpdated(ArchiveCommand.TargetProgress.from(job));

        runJob(
                accountArgument, platform, job);

        finalizeJob(job);
        progressListener.targetCompleted(ArchiveCommand.TargetProgress.from(job));
        store.log("ACCOUNT-COMPLETE " + platformName + " " +
                accountArgument + " posts=" + job.postsArchived +
                " shared-collection-warc");
        out.printf("%sArchived %d posts from %s%n",
                resumed ? "Resumed and " : "",
                job.postsArchived,
                accountArgument);
        ArchiveCommand.printMediaSummary(
                out, job.includeMedia, new MediaCaptureStats(
                        job.mediaCaptured,
                        job.mediaFailed,
                        job.mediaSkipped,
                        job.mediaBytes));
    }

    boolean isDisabled(
            String accountArgument,
            ArchivePlatform platform,
            boolean localTimeline) {
        String key = CollectionStore.accountKey(
                localTimeline ? "mastodon-local" : platform.externalName(),
                accountArgument);
        synchronized (collectionState) {
            CollectionState.AccountProgress account =
                    collectionState.accounts.get(key);
            return account != null && account.disabled;
        }
    }

    void recordFailure(
            String accountArgument,
            ArchivePlatform platform,
            boolean localTimeline,
            Throwable failure) throws IOException {
        String key = CollectionStore.accountKey(
                localTimeline ? "mastodon-local" : platform.externalName(),
                accountArgument);
        Throwable cause = failure instanceof java.io.UncheckedIOException
                ? failure.getCause()
                : failure;
        String message = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
        String now = Instant.now().toString();
        synchronized (collectionState) {
            CollectionState.AccountProgress progress =
                    collectionState.accounts.computeIfAbsent(
                            key, ignored -> new CollectionState.AccountProgress());
            progress.platform = localTimeline
                    ? "mastodon-local"
                    : platform.externalName();
            progress.account = accountArgument;
            progress.lastAttemptAt = now;
            progress.lastFailureAt = now;
            progress.failureMessage = message;
            progress.failureKind = permanentFailure(message)
                    ? "permanent"
                    : "transient";
            progress.consecutiveFailures++;
            progress.updatedAt = now;
            store.saveCollectionState(collectionState);
        }
    }

    private static boolean permanentFailure(String message) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("response 404") ||
                normalized.contains("response 410") ||
                normalized.contains("account not found") ||
                normalized.contains("actor not found") ||
                normalized.contains("profile not found");
    }

    private CollectionJob createJob(
            String key,
            String accountArgument,
            ArchivePlatform platform,
            boolean localTimeline,
            CollectionState.AccountProgress previous) throws IOException {
        var job = new CollectionJob();
        job.key = key;
        job.platform = platform.externalName();
        job.account = accountArgument;
        job.localTimeline = localTimeline;
        job.stopIdentity =
                previous == null ? null : previous.latestIdentity;
        job.since =
                options.since() == null ? null : options.since().toString();
        job.until =
                options.until() == null ? null : options.until().toString();
        job.maxPosts = options.maxPosts();
        job.maxPages = options.maxPages();
        job.includeMedia = options.includeMedia();
        job.includeVideo = options.includeVideo();
        job.allVideoVariants = options.allVideoVariants();
        job.maxMediaBytes = options.maxMediaBytes();
        job.maxTotalMediaBytes = options.maxTotalMediaBytes();
        job.pageSize = options.pageSize() == null
                ? (platform == ArchivePlatform.MASTODON ? 40 : 100)
                : options.pageSize();
        job.userAgent = options.userAgent();
        job.service = options.service() == null
                ? AtProtocolClient.PUBLIC_APPVIEW
                : options.service();
        job.startedAt = Instant.now().toString();
        store.saveJob(job);
        store.log("START " + job.platform + " " + accountArgument +
                " shared-collection-warc");
        return job;
    }

    private void resumeJob(
            String accountArgument,
            CollectionJob job) throws IOException {
        progress.report(
                "Resuming " + accountArgument + " at cursor " + job.cursor);
        store.log("RESUME " + job.platform + " " + accountArgument +
                " cursor=" + job.cursor);
    }

    private void runJob(
            String accountArgument,
            ArchivePlatform platform,
            CollectionJob job) throws IOException {
        try (var writer = runWriters.lease(CollectionWarcStream.POSTS)) {
            runArchiver(
                    accountArgument, platform, job, writer);
            job.posts.setCommittedOffset(writer.position());
            store.saveJob(job);
        }
    }

    private void runArchiver(
            String accountArgument,
            ArchivePlatform platform,
            CollectionJob job,
            org.netpreserve.jwarc.WarcWriter writer)
            throws IOException {
        SplitCheckpointingMediaSink mediaSink = job.includeMedia
                ? mediaSink(job)
                : null;
        try (mediaSink) {
            drainPending(job, mediaSink);
            ArchiveRunOptions runOptions = runOptions(job);
            ArchiveCheckpoint checkpoint = page -> {
                job.cursor = page.cursor();
                job.newestIdentity = page.newestIdentity();
                job.postsArchived = page.postsArchived();
                job.pagesFetched = page.pagesFetched();
                job.posts.setCommittedOffset(writer.position());
                store.saveJob(job);
                progressListener.targetUpdated(
                        ArchiveCommand.TargetProgress.from(job));
            };
            if (platform == ArchivePlatform.MASTODON) {
                if (job.localTimeline) {
                    runMastodonLocalTimeline(
                            accountArgument, job, mediaSink,
                            writer, runOptions, checkpoint);
                } else {
                    runMastodon(
                            accountArgument, job, mediaSink,
                            writer, runOptions, checkpoint);
                }
            } else {
                runBluesky(
                        accountArgument, job, mediaSink,
                        writer, runOptions, checkpoint);
            }
        }
    }

    private void drainPending(
            CollectionJob job, SplitCheckpointingMediaSink mediaSink) {
        if (mediaSink == null ||
                (job.pendingMedia.isEmpty() &&
                        job.pendingVideo.isEmpty())) {
            return;
        }
        progress.report("Resuming " +
                (job.pendingMedia.size() + job.pendingVideo.size()) +
                " pending media resources...");
        mediaSink.drainPending();
    }

    private void runMastodon(
            String accountArgument,
            CollectionJob job,
            SplitCheckpointingMediaSink mediaSink,
            org.netpreserve.jwarc.WarcWriter writer,
            ArchiveRunOptions runOptions,
            ArchiveCheckpoint checkpoint) throws IOException {
        MastodonAccountReference reference =
                MastodonAccountReference.parse(accountArgument);
        new MastodonAccountArchiver(
                reference.server(),
                job.userAgent,
                writer,
                job.pageSize,
                mediaSink,
                progress,
                job.includeVideo)
                .archive(reference.account(), runOptions, checkpoint);
    }

    private void runBluesky(
            String accountArgument,
            CollectionJob job,
            SplitCheckpointingMediaSink mediaSink,
            org.netpreserve.jwarc.WarcWriter writer,
            ArchiveRunOptions runOptions,
            ArchiveCheckpoint checkpoint) throws IOException {
        BlueskyAccountReference reference =
                BlueskyAccountReference.parse(accountArgument);
        new BlueskyAccountArchiver(
                job.service,
                job.userAgent,
                writer,
                job.pageSize,
                mediaSink,
                progress,
                job.includeVideo)
                .archive(reference.actor(), runOptions, checkpoint);
    }

    private void runMastodonLocalTimeline(
            String serverArgument,
            CollectionJob job,
            SplitCheckpointingMediaSink mediaSink,
            org.netpreserve.jwarc.WarcWriter writer,
            ArchiveRunOptions runOptions,
            ArchiveCheckpoint checkpoint) throws IOException {
        MastodonServerReference reference =
                MastodonServerReference.parse(serverArgument);
        new MastodonLocalTimelineArchiver(
                reference.server(), job.userAgent, writer, job.pageSize,
                mediaSink, progress, job.includeVideo)
                .archive(runOptions, checkpoint);
    }

    private SplitCheckpointingMediaSink mediaSink(CollectionJob job) {
        return new SplitCheckpointingMediaSink(
                store,
                job,
                () -> runWriters.lease(CollectionWarcStream.MEDIA),
                () -> runWriters.lease(CollectionWarcStream.VIDEO),
                job.userAgent,
                job.maxMediaBytes,
                job.maxTotalMediaBytes,
                job.allVideoVariants,
                progress,
                updated -> progressListener.targetUpdated(
                        ArchiveCommand.TargetProgress.from(updated)));
    }

    private static ArchiveRunOptions runOptions(CollectionJob job) {
        return new ArchiveRunOptions(
                job.since == null ? null : Instant.parse(job.since),
                job.until == null ? null : Instant.parse(job.until),
                job.maxPosts,
                job.maxPages,
                job.stopIdentity,
                job.cursor,
                job.newestIdentity,
                job.postsArchived,
                job.pagesFetched);
    }

    private void finalizeJob(CollectionJob job)
            throws IOException {
        var progress = new CollectionState.AccountProgress();
        progress.platform = job.localTimeline
                ? "mastodon-local"
                : job.platform;
        progress.account = job.account;
        synchronized (collectionState) {
            CollectionState.AccountProgress previous =
                    collectionState.accounts.get(job.key);
            progress.latestIdentity = job.newestIdentity != null
                    ? job.newestIdentity
                    : previous == null ? null : previous.latestIdentity;
            progress.updatedAt = Instant.now().toString();
            progress.lastAttemptAt = progress.updatedAt;
            progress.lastSuccessAt = progress.updatedAt;
            progress.lastFailureAt = null;
            progress.failureMessage = null;
            progress.failureKind = null;
            progress.consecutiveFailures = 0;
            progress.disabled = previous != null && previous.disabled;
            collectionState.accounts.put(job.key, progress);
            store.saveCollectionState(collectionState);
        }
        store.deleteJob(job.key);
    }
}
