package org.netpreserve.warcana.bluesky;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.bluesky.model.FeedPage;
import org.netpreserve.warcana.bluesky.model.Profile;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Archives a Bluesky profile and all pages of its author feed.
 */
public class BlueskyAccountArchiver {
    private final BlueskyApi api;
    private final int pageSize;

    public BlueskyAccountArchiver(String service, String userAgent,
                                  WarcWriter warcWriter, int pageSize) {
        this(new AtProtocolClient(service, userAgent, warcWriter), pageSize);
    }

    BlueskyAccountArchiver(BlueskyApi api, int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
        this.api = api;
        this.pageSize = pageSize;
    }

    public ArchiveResult archive(String actor) throws IOException {
        Profile profile = api.getProfile(actor);
        String cursor = null;
        int pages = 0;
        int feedItems = 0;
        Set<String> seenCursors = new HashSet<>();

        while (true) {
            FeedPage page = api.getAuthorFeed(profile.did(), cursor, pageSize);
            pages++;
            feedItems += page.feed().size();
            if (page.feed().isEmpty() || page.cursor() == null || page.cursor().isBlank()) {
                break;
            }
            if (!seenCursors.add(page.cursor())) {
                throw new IOException("Bluesky pagination repeated cursor " + page.cursor());
            }
            cursor = page.cursor();
        }

        return new ArchiveResult(profile, feedItems, pages);
    }

    public record ArchiveResult(Profile profile, int feedItemsArchived, int pagesFetched) {
    }
}
