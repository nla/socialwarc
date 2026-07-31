package org.netpreserve.warcana.common;

import java.io.IOException;

/**
 * Called only after a complete API page and all media discovered on that page
 * have been written.
 */
@FunctionalInterface
public interface ArchiveCheckpoint {
    ArchiveCheckpoint NONE = checkpoint -> {
    };

    void save(PageCheckpoint checkpoint) throws IOException;

    record PageCheckpoint(
            String cursor,
            String newestIdentity,
            int postsArchived,
            int pagesFetched,
            boolean complete) {
    }
}
