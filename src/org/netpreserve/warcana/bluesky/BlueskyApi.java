package org.netpreserve.warcana.bluesky;

import org.netpreserve.warcana.bluesky.model.FeedPage;
import org.netpreserve.warcana.bluesky.model.Profile;

import java.io.IOException;

interface BlueskyApi {
    Profile getProfile(String actor) throws IOException;

    FeedPage getAuthorFeed(String actor, String cursor, int limit) throws IOException;
}
