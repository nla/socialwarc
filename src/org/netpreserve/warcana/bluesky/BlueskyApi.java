package org.netpreserve.warcana.bluesky;

import java.io.IOException;

interface BlueskyApi {
    Profile getProfile(String actor) throws IOException;

    FeedPage getAuthorFeed(String actor, String cursor, int limit) throws IOException;
}
