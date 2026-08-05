package org.netpreserve.socialwarc.mastodon.model;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

// https://docs.joinmastodon.org/entities/CustomEmoji/
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomEmoji(
        String shortcode,
        String url,
        String staticUrl,
        boolean visibleInPicker,
        String category
) {
}
