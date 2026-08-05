package org.netpreserve.socialwarc.mastodon.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

// https://docs.joinmastodon.org/entities/Poll/
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Poll(
        @NonNull String id,
        @NonNull String expiresAt,
        @Nullable Boolean expired,
        boolean multiple,
        @NonNull List<Option> options,
        @Nullable Integer votesCount,
        @Nullable Integer votersCount,
        @Nullable List<CustomEmoji> emojis
) {
    // https://docs.joinmastodon.org/entities/Poll/#Option
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Option(
            @NonNull String title,
            @Nullable Long votesCount
    ) {
    }
}
