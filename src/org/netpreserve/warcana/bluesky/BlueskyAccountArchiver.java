package org.netpreserve.warcana.bluesky;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.bluesky.model.FeedPage;
import org.netpreserve.warcana.bluesky.model.Profile;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.ArchiveCheckpoint;
import org.netpreserve.warcana.common.ArchiveRunOptions;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Archives a Bluesky profile and all pages of its author feed.
 */
public class BlueskyAccountArchiver {
    private final BlueskyApi api;
    private final int pageSize;
    private final MediaSink mediaSink;
    private final ProgressReporter progress;
    private final boolean includeVideo;

    public BlueskyAccountArchiver(
            String service,
            String userAgent,
            WarcWriter warcWriter,
            int pageSize,
            MediaSink mediaSink,
            ProgressReporter progress,
            boolean includeVideo) {
        this(new AtProtocolClient(service, userAgent, warcWriter),
                pageSize, mediaSink, progress, includeVideo);
    }

    BlueskyAccountArchiver(
            BlueskyApi api,
            int pageSize,
            MediaSink mediaSink,
            ProgressReporter progress) {
        this(api, pageSize, mediaSink, progress, true);
    }

    private BlueskyAccountArchiver(
            BlueskyApi api,
            int pageSize,
            MediaSink mediaSink,
            ProgressReporter progress,
            boolean includeVideo) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
        this.api = api;
        this.pageSize = pageSize;
        this.mediaSink = mediaSink;
        this.progress = progress;
        this.includeVideo = includeVideo;
    }

    public ArchiveResult archive(String actor) throws IOException {
        return archive(
                actor,
                ArchiveRunOptions.unbounded(),
                ArchiveCheckpoint.NONE);
    }

    public ArchiveResult archive(
            String actor,
            ArchiveRunOptions options,
            ArchiveCheckpoint checkpoint) throws IOException {
        progress.report("Looking up Bluesky account: " + actor);
        Profile profile = api.getProfile(actor);
        progress.report("Found Bluesky account: " + profile.handle());
        if (mediaSink != null) {
            mediaSink.captureAll(profile.mediaUrls());
        }
        String cursor = options.cursor();
        int pages = options.pagesAlreadyFetched();
        int feedItems = options.postsAlreadyArchived();
        String newestIdentity = options.newestIdentity();
        boolean complete = false;
        Set<String> seenCursors = new HashSet<>();

        while (true) {
            if (options.pageLimitReached(pages) ||
                    options.postLimitReached(feedItems)) {
                break;
            }
            progress.report("Fetching Bluesky feed page " + (pages + 1) +
                    " for " + profile.handle() + "...");
            FeedPage page = api.getAuthorFeed(profile.did(), cursor, pageSize);
            pages++;
            var selected = new java.util.ArrayList<org.netpreserve.warcana.bluesky.model.FeedItem>();
            boolean reachedExisting = false;
            boolean reachedSince = false;
            for (var item : page.feed()) {
                if (options.stopIdentity() != null &&
                        options.stopIdentity().equals(item.identity())) {
                    reachedExisting = true;
                    break;
                }
                var createdAt = item.toGenericPost().createdAt();
                if (createdAt != null && options.since() != null &&
                        createdAt.isBefore(options.since())) {
                    reachedSince = true;
                    break;
                }
                if (createdAt != null && options.until() != null &&
                        createdAt.isAfter(options.until())) {
                    continue;
                }
                if (options.postLimitReached(feedItems + selected.size())) {
                    break;
                }
                if (newestIdentity == null) {
                    newestIdentity = item.identity();
                }
                selected.add(item);
            }
            feedItems += selected.size();
            if (selected.size() == page.feed().size()) {
                progress.report("Fetched " + page.feed().size() +
                        (page.feed().size() == 1 ? " feed item (" : " feed items (") +
                        feedItems + " total)");
            } else {
                progress.report("Selected " + selected.size() + " of " + page.feed().size() +
                        (page.feed().size() == 1 ? " fetched feed item (" : " fetched feed items (") +
                        feedItems + " total)");
            }
            if (mediaSink != null) {
                var mediaUrls = new java.util.ArrayList<String>();
                selected.forEach(item ->
                        mediaUrls.addAll(item.mediaUrls(false)));
                mediaSink.discover(mediaUrls);
                if (includeVideo) {
                    var videoUrls = new java.util.ArrayList<String>();
                    selected.forEach(item ->
                            videoUrls.addAll(item.videoUrls()));
                    mediaSink.discoverVideo(videoUrls);
                }
            }
            if (page.feed().isEmpty() || page.cursor() == null || page.cursor().isBlank()) {
                complete = true;
                checkpoint.save(new ArchiveCheckpoint.PageCheckpoint(
                        null, newestIdentity, feedItems, pages, true));
                break;
            }
            boolean bounded = reachedExisting || reachedSince ||
                    options.postLimitReached(feedItems) ||
                    options.pageLimitReached(pages);
            if (reachedExisting || reachedSince) {
                complete = true;
            }
            checkpoint.save(new ArchiveCheckpoint.PageCheckpoint(
                    bounded ? null : page.cursor(),
                    newestIdentity,
                    feedItems,
                    pages,
                    complete));
            if (mediaSink != null) {
                mediaSink.drain();
            }
            if (bounded) {
                break;
            }
            if (!seenCursors.add(page.cursor())) {
                throw new IOException("Bluesky pagination repeated cursor " + page.cursor());
            }
            cursor = page.cursor();
        }

        MediaCaptureStats media = mediaSink == null
                ? new MediaCaptureStats(0, 0, 0, 0)
                : mediaSink.stats();
        return new ArchiveResult(
                profile, feedItems, pages, media,
                newestIdentity, cursor, complete);
    }

    public record ArchiveResult(
            Profile profile,
            int feedItemsArchived,
            int pagesFetched,
            MediaCaptureStats media,
            String newestIdentity,
            String nextCursor,
            boolean complete) {
    }
}
