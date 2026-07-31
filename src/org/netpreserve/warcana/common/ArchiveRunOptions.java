package org.netpreserve.warcana.common;

import java.time.Instant;

/**
 * Bounds and resume information for one account archive run.
 */
public record ArchiveRunOptions(
        Instant since,
        Instant until,
        int maxPosts,
        int maxPages,
        String stopIdentity,
        String cursor,
        String newestIdentity,
        int postsAlreadyArchived,
        int pagesAlreadyFetched) {

    public static ArchiveRunOptions unbounded() {
        return new ArchiveRunOptions(null, null, 0, 0, null, null, null, 0, 0);
    }

    public boolean postLimitReached(int posts) {
        return maxPosts > 0 && posts >= maxPosts;
    }

    public boolean pageLimitReached(int pages) {
        return maxPages > 0 && pages >= maxPages;
    }
}
