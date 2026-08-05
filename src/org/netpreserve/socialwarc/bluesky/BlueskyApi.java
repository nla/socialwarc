package org.netpreserve.socialwarc.bluesky;

import org.netpreserve.socialwarc.bluesky.model.FeedPage;
import org.netpreserve.socialwarc.bluesky.model.Profile;

import java.io.IOException;

interface BlueskyApi {
    Profile getProfile(String actor) throws IOException;

    FeedPage getAuthorFeed(String actor, String cursor, int limit) throws IOException;
}
