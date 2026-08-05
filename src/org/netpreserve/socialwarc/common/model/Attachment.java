package org.netpreserve.socialwarc.common.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record Attachment(
        String url,
        String type,
        @Nullable String altText,
        List<Source> sources,
        @Nullable String title,
        @Nullable String description,
        @Nullable String thumbnailUrl
) {
    public record Source(String url, String contentType, Long bitrate, Integer width, Integer height) {
    }
}
