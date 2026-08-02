package org.netpreserve.warcana.mastodon.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jsoup.nodes.Entities;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.netpreserve.warcana.common.model.Attachment;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

// https://docs.joinmastodon.org/entities/PreviewCard/
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PreviewCard(
        @NonNull String url,
        @NonNull String title,
        @NonNull String description,
        @NonNull Type type,
        @NonNull String authorName,
        @Nullable String authorUrl,
        @Nullable String providerName,
        @Nullable String providerUrl,
        @Nullable String html,
        @Nullable Long width,
        @Nullable Long height,
        @Nullable String image,
        @Nullable String imageDescription,
        @Nullable String embedUrl,
        @Nullable String blurhash,
        @Nullable String language,
        @Nullable Instant publishedAt,
        @Nullable List<Author> authors
) {
    public Attachment toGenericAttachment() {
        return new Attachment(
                url,
                "link",
                unescape(imageDescription),
                List.of(),
                unescape(title),
                unescape(description),
                image);
    }

    private static @Nullable String unescape(@Nullable String value) {
        return value == null ? null : Entities.unescape(value);
    }

    // https://docs.joinmastodon.org/entities/PreviewCard/#type
    public enum Type {
        @JsonProperty("link") LINK,
        @JsonProperty("photo") PHOTO,
        @JsonProperty("video") VIDEO,
        @JsonProperty("rich") RICH
    }

    record Author(
            String name,
            String url,
            @Nullable Account account) {
    }
}
