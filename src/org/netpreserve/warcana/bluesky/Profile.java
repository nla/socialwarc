package org.netpreserve.warcana.bluesky;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record Profile(
        @NonNull String did,
        @NonNull String handle,
        @Nullable String displayName,
        @Nullable String description,
        @Nullable String avatar,
        @Nullable String banner,
        long followersCount,
        long followsCount,
        long postsCount) {
}
