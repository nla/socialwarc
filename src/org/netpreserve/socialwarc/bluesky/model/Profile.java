package org.netpreserve.socialwarc.bluesky.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Stream;

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

    public List<String> mediaUrls() {
        return Stream.of(avatar, banner)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
