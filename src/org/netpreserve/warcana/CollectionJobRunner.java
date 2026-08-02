package org.netpreserve.warcana;

import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.warcana.bluesky.AtProtocolClient;
import org.netpreserve.warcana.bluesky.BlueskyAccountArchiver;
import org.netpreserve.warcana.bluesky.BlueskyAccountReference;
import org.netpreserve.warcana.common.ArchiveCheckpoint;
import org.netpreserve.warcana.common.ArchiveRunOptions;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.collection.CollectionJob;
import org.netpreserve.warcana.common.collection.CollectionState;
import org.netpreserve.warcana.common.collection.CollectionStore;
import org.netpreserve.warcana.common.collection.CollectionWarcStream;
import org.netpreserve.warcana.common.collection.SplitCheckpointingMediaSink;
import org.netpreserve.warcana.mastodon.MastodonAccountArchiver;
import org.netpreserve.warcana.mastodon.MastodonAccountReference;
import org.netpreserve.warcana.mastodon.MastodonLocalTimelineArchiver;
import org.netpreserve.warcana.mastodon.MastodonServerReference;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

final class CollectionJobRunner {
    private final CollectionStore store;
    private final ArchiveOptions options;
    private final PrintStream out;
    private final ProgressReporter progress;

    CollectionJobRunner(
            CollectionStore store,
            ArchiveOptions options,
            PrintStream out,
            ProgressReporter progress) {
        this.store = store;
        this.options = options;
        this.out = out;
        this.progress = progress;
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
        CollectionState collectionState = store.loadCollectionState();
        CollectionState.AccountProgress previous =
                collectionState.accounts.get(key);
        CollectionJob job = store.loadJob(key);

        if (job != null &&
                Files.exists(store.root().resolve(
                        job.posts.finalWarc()))) {
            finalizeJob(collectionState, job);
            store.log("RECOVERED-COMPLETE " + platformName + " " +
                    accountArgument + " warc=" +
                    job.posts.finalWarc());
            out.printf("Recovered completed archive for %s: %s%n",
                    accountArgument,
                    store.root().resolve(job.posts.finalWarc()));
            return;
        }

        boolean resumed = job != null;
        if (job == null) {
            job = createJob(
                    key, accountArgument, platform, localTimeline,
                    previous, collectionState);
        } else {
            resumeJob(accountArgument, job, collectionState);
        }

        runJob(
                accountArgument, platform, platformName, job,
                collectionState, resumed);

        for (var stream : CollectionWarcStream.values()) {
            stream.copyToState(job, collectionState);
        }
        finalizeJob(collectionState, job);
        store.log("ACCOUNT-COMPLETE " + platformName + " " +
                accountArgument + " posts=" + job.postsArchived +
                " openWarc=" + job.posts.openWarc());
        out.printf("%sArchived %d posts from %s into %s%n",
                resumed ? "Resumed and " : "",
                job.postsArchived,
                accountArgument,
                store.root().resolve(job.posts.openWarc()));
        ArchiveCommand.printMediaSummary(
                out, job.includeMedia, new MediaCaptureStats(
                        job.mediaCaptured,
                        job.mediaFailed,
                        job.mediaSkipped,
                        job.mediaBytes));
    }

    private CollectionJob createJob(
            String key,
            String accountArgument,
            ArchivePlatform platform,
            boolean localTimeline,
            CollectionState.AccountProgress previous,
            CollectionState collectionState) throws IOException {
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
        ensurePostsStream(collectionState);
        CollectionWarcStream.POSTS.copyToJob(collectionState, job);
        store.saveJob(job);
        store.log("START " + job.platform + " " + accountArgument +
                " " + job.posts.openWarc());
        return job;
    }

    private void ensurePostsStream(CollectionState state)
            throws IOException {
        if (state.posts.openWarc() != null) {
            return;
        }
        String timestamp = DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String basename = "warcana-posts-" + timestamp + "-" +
                UUID.randomUUID().toString().substring(0, 8) + ".warc.gz";
        state.posts.setPaths(
                "warcs/" + basename + ".open",
                "warcs/" + basename);
        state.posts.setCommittedOffset(0);
        store.saveCollectionState(state);
    }

    private void resumeJob(
            String accountArgument,
            CollectionJob job,
            CollectionState collectionState) throws IOException {
        if (collectionState.posts.openWarc() == null) {
            CollectionWarcStream.POSTS.copyToState(job, collectionState);
            store.saveCollectionState(collectionState);
        } else if (!collectionState.posts.openWarc().equals(
                job.posts.openWarc())) {
            throw new IOException(
                    "Another interrupted account owns the shared WARC: " +
                            collectionState.posts.openWarc());
        }
        progress.report(
                "Resuming " + accountArgument + " at WARC offset " +
                        job.posts.committedOffset());
        store.log("RESUME " + job.platform + " " + accountArgument +
                " offset=" + job.posts.committedOffset());
    }

    private void runJob(
            String accountArgument,
            ArchivePlatform platform,
            String platformName,
            CollectionJob job,
            CollectionState collectionState,
            boolean resumed) throws IOException {
        Function<String, Warcinfo> warcinfoFactory =
                warcinfoFactory(job, platformName, accountArgument);
        try (var writer = new RollingCollectionWarcWriter(
                store,
                collectionState,
                job,
                CollectionWarcStream.POSTS,
                warcinfoFactory,
                progress)) {
            if (!resumed) {
                writer.write(warcinfoFactory.apply(
                        Path.of(job.posts.finalWarc())
                                .getFileName().toString()));
                job.posts.setCommittedOffset(writer.position());
                store.saveJob(job);
            }
            runArchiver(
                    accountArgument, platform, job, collectionState,
                    writer, warcinfoFactory);
            job.posts.setCommittedOffset(writer.position());
            store.saveJob(job);
        }
    }

    private Function<String, Warcinfo> warcinfoFactory(
            CollectionJob job,
            String platformName,
            String accountArgument) {
        return filename -> {
            Map<String, List<String>> metadata =
                    ArchiveCommand.archiveMetadata(
                            job.userAgent,
                            platformName,
                            accountArgument,
                            job.includeMedia,
                            job.includeVideo,
                            job.allVideoVariants,
                            job.maxMediaBytes,
                            job.maxTotalMediaBytes);
            metadata.put("collection", List.of(store.root().toString()));
            if (job.localTimeline) {
                metadata.remove("account");
                metadata.put("scope", List.of("local-public-timeline"));
                metadata.put("server", List.of(
                        MastodonServerReference.parse(accountArgument).server()));
            }
            metadata.put(
                    "incremental-stop-identity",
                    List.of(job.stopIdentity == null
                            ? ""
                            : job.stopIdentity));
            return new Warcinfo.Builder()
                    .filename(filename)
                    .fields(metadata)
                    .build();
        };
    }

    private void runArchiver(
            String accountArgument,
            ArchivePlatform platform,
            CollectionJob job,
            CollectionState collectionState,
            RollingCollectionWarcWriter writer,
            Function<String, Warcinfo> warcinfoFactory)
            throws IOException {
        SplitCheckpointingMediaSink mediaSink = job.includeMedia
                ? mediaSink(job, collectionState, warcinfoFactory)
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
            RollingCollectionWarcWriter writer,
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
            RollingCollectionWarcWriter writer,
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
            RollingCollectionWarcWriter writer,
            ArchiveRunOptions runOptions,
            ArchiveCheckpoint checkpoint) throws IOException {
        MastodonServerReference reference =
                MastodonServerReference.parse(serverArgument);
        new MastodonLocalTimelineArchiver(
                reference.server(), job.userAgent, writer, job.pageSize,
                mediaSink, progress, job.includeVideo)
                .archive(runOptions, checkpoint);
    }

    private SplitCheckpointingMediaSink mediaSink(
            CollectionJob job,
            CollectionState collectionState,
            Function<String, Warcinfo> warcinfoFactory) {
        return new SplitCheckpointingMediaSink(
                store,
                job,
                () -> new RollingCollectionWarcWriter(
                        store,
                        collectionState,
                        job,
                        CollectionWarcStream.MEDIA,
                        warcinfoFactory,
                        progress),
                () -> new RollingCollectionWarcWriter(
                        store,
                        collectionState,
                        job,
                        CollectionWarcStream.VIDEO,
                        warcinfoFactory,
                        progress),
                job.userAgent,
                job.maxMediaBytes,
                job.maxTotalMediaBytes,
                job.allVideoVariants,
                progress);
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

    private void finalizeJob(
            CollectionState collectionState, CollectionJob job)
            throws IOException {
        var progress = new CollectionState.AccountProgress();
        progress.platform = job.localTimeline
                ? "mastodon-local"
                : job.platform;
        progress.account = job.account;
        CollectionState.AccountProgress previous =
                collectionState.accounts.get(job.key);
        progress.latestIdentity = job.newestIdentity != null
                ? job.newestIdentity
                : previous == null ? null : previous.latestIdentity;
        progress.updatedAt = Instant.now().toString();
        collectionState.accounts.put(job.key, progress);
        store.saveCollectionState(collectionState);
        store.deleteJob(job.key);
    }
}
