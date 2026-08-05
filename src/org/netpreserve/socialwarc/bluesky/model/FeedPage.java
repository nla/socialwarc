package org.netpreserve.socialwarc.bluesky.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record FeedPage(@Nullable String cursor, List<FeedItem> feed) {
}
