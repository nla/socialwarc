package org.netpreserve.warcana.mastodon;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.common.ArchiveCheckpoint;
import org.netpreserve.warcana.common.ArchiveRunOptions;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.mastodon.model.Status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Archives every page exposed by a Mastodon server's local public timeline. */
public class MastodonLocalTimelineArchiver {
    private final MastodonApi client;
    private final int pageSize;
    private final MediaSink mediaSink;
    private final ProgressReporter progress;
    private final boolean includeVideo;

    public MastodonLocalTimelineArchiver(
            String server,
            String userAgent,
            WarcWriter warcWriter,
            int pageSize,
            MediaSink mediaSink,
            ProgressReporter progress,
            boolean includeVideo) {
        this(new MastodonClient(server, userAgent, warcWriter), pageSize,
                mediaSink, progress, includeVideo);
    }

    MastodonLocalTimelineArchiver(
            MastodonApi client,
            int pageSize,
            MediaSink mediaSink,
            ProgressReporter progress,
            boolean includeVideo) {
        if (pageSize < 1 || pageSize > 40) {
            throw new IllegalArgumentException(
                    "Page size must be between 1 and 40");
        }
        this.client = client;
        this.pageSize = pageSize;
        this.mediaSink = mediaSink;
        this.progress = progress;
        this.includeVideo = includeVideo;
    }

    public ArchiveResult archive(
            ArchiveRunOptions options,
            ArchiveCheckpoint checkpoint) throws IOException {
        String maxId = options.cursor();
        int pages = options.pagesAlreadyFetched();
        int statusesArchived = options.postsAlreadyArchived();
        String newestIdentity = options.newestIdentity();
        boolean complete = false;
        Set<String> cursors = new HashSet<>();

        while (!options.pageLimitReached(pages) &&
                !options.postLimitReached(statusesArchived)) {
            progress.report("Fetching Mastodon local timeline page " +
                    (pages + 1) + "...");
            var statuses = client.getPublicTimeline(
                    true, null, maxId, pageSize);
            pages++;
            var selected = new ArrayList<Status>();
            boolean reachedExisting = false;
            boolean reachedSince = false;
            for (Status status : statuses) {
                if (options.stopIdentity() != null &&
                        options.stopIdentity().equals(status.id())) {
                    reachedExisting = true;
                    break;
                }
                if (options.since() != null &&
                        status.createdAt().isBefore(options.since())) {
                    reachedSince = true;
                    break;
                }
                if (options.until() != null &&
                        status.createdAt().isAfter(options.until())) {
                    continue;
                }
                if (options.postLimitReached(
                        statusesArchived + selected.size())) {
                    break;
                }
                if (newestIdentity == null) {
                    newestIdentity = status.id();
                }
                selected.add(status);
            }
            statusesArchived += selected.size();
            progress.report("Selected " + selected.size() + " of " +
                    statuses.size() + " fetched statuses (" +
                    statusesArchived + " total)");
            discoverMedia(selected);

            if (statuses.isEmpty()) {
                complete = true;
                checkpoint.save(new ArchiveCheckpoint.PageCheckpoint(
                        null, newestIdentity, statusesArchived, pages, true));
                break;
            }

            String nextMaxId = statuses.getLast().id();
            boolean bounded = reachedExisting || reachedSince ||
                    options.postLimitReached(statusesArchived) ||
                    options.pageLimitReached(pages);
            if (reachedExisting || reachedSince) {
                complete = true;
            }
            checkpoint.save(new ArchiveCheckpoint.PageCheckpoint(
                    bounded ? null : nextMaxId, newestIdentity,
                    statusesArchived, pages, complete));
            if (mediaSink != null) {
                mediaSink.drain();
            }
            if (bounded) {
                break;
            }
            if (!cursors.add(nextMaxId)) {
                throw new IOException(
                        "Mastodon pagination repeated status ID " + nextMaxId);
            }
            maxId = nextMaxId;
        }

        MediaCaptureStats media = mediaSink == null
                ? new MediaCaptureStats(0, 0, 0, 0)
                : mediaSink.stats();
        return new ArchiveResult(statusesArchived, pages, media,
                newestIdentity, maxId, complete);
    }

    private void discoverMedia(List<Status> statuses) {
        if (mediaSink == null) return;
        var mediaUrls = new ArrayList<String>();
        statuses.forEach(status -> mediaUrls.addAll(status.mediaUrls(false)));
        mediaSink.discover(mediaUrls);
        if (includeVideo) {
            var videoUrls = new ArrayList<String>();
            statuses.forEach(status -> videoUrls.addAll(status.videoUrls()));
            mediaSink.discoverVideo(videoUrls);
        }
    }

    public record ArchiveResult(
            int statusesArchived,
            int pagesFetched,
            MediaCaptureStats media,
            String newestIdentity,
            String nextCursor,
            boolean complete) {
    }
}
