package org.netpreserve.warcana.bluesky;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.bluesky.model.FeedPage;
import org.netpreserve.warcana.bluesky.model.Profile;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.ProgressReporter;

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

    public BlueskyAccountArchiver(String service, String userAgent,
                                  WarcWriter warcWriter, int pageSize) {
        this(service, userAgent, warcWriter, pageSize, null);
    }

    public BlueskyAccountArchiver(String service, String userAgent,
                                  WarcWriter warcWriter, int pageSize,
                                  MediaSink mediaSink) {
        this(service, userAgent, warcWriter, pageSize, mediaSink, ProgressReporter.NONE);
    }

    public BlueskyAccountArchiver(String service, String userAgent,
                                  WarcWriter warcWriter, int pageSize,
                                  MediaSink mediaSink, ProgressReporter progress) {
        this(service, userAgent, warcWriter, pageSize,
                mediaSink, progress, true);
    }

    public BlueskyAccountArchiver(String service, String userAgent,
                                  WarcWriter warcWriter, int pageSize,
                                  MediaSink mediaSink, ProgressReporter progress,
                                  boolean includeVideo) {
        this(new AtProtocolClient(service, userAgent, warcWriter),
                pageSize, mediaSink, progress, includeVideo);
    }

    BlueskyAccountArchiver(BlueskyApi api, int pageSize) {
        this(api, pageSize, null);
    }

    BlueskyAccountArchiver(BlueskyApi api, int pageSize, MediaSink mediaSink) {
        this(api, pageSize, mediaSink, ProgressReporter.NONE);
    }

    BlueskyAccountArchiver(BlueskyApi api, int pageSize, MediaSink mediaSink,
                           ProgressReporter progress) {
        this(api, pageSize, mediaSink, progress, true);
    }

    BlueskyAccountArchiver(BlueskyApi api, int pageSize, MediaSink mediaSink,
                           ProgressReporter progress, boolean includeVideo) {
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
        progress.report("Looking up Bluesky account: " + actor);
        Profile profile = api.getProfile(actor);
        progress.report("Found Bluesky account: " + profile.handle());
        if (mediaSink != null) {
            mediaSink.captureAll(profile.mediaUrls());
        }
        String cursor = null;
        int pages = 0;
        int feedItems = 0;
        Set<String> seenCursors = new HashSet<>();

        while (true) {
            progress.report("Fetching Bluesky feed page " + (pages + 1) + "...");
            FeedPage page = api.getAuthorFeed(profile.did(), cursor, pageSize);
            pages++;
            feedItems += page.feed().size();
            progress.report("Fetched " + page.feed().size() +
                    (page.feed().size() == 1 ? " feed item (" : " feed items (") +
                    feedItems + " total)");
            if (mediaSink != null) {
                page.feed().forEach(item ->
                        mediaSink.captureAll(item.mediaUrls(includeVideo)));
            }
            if (page.feed().isEmpty() || page.cursor() == null || page.cursor().isBlank()) {
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
        return new ArchiveResult(profile, feedItems, pages, media);
    }

    public record ArchiveResult(
            Profile profile,
            int feedItemsArchived,
            int pagesFetched,
            MediaCaptureStats media) {
    }
}
