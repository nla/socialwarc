package org.netpreserve.socialwarc.mastodon.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.netpreserve.socialwarc.common.model.Attachment;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.List;

// https://docs.joinmastodon.org/entities/MediaAttachment/
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MediaAttachment(
        @NonNull String id,
        @NonNull String type,
        @NonNull String url,
        @Nullable String previewUrl,
        @Nullable String previewRemoteUrl,
        @Nullable String remoteUrl,
        @Nullable String textUrl, // deprecated
        @Nullable Meta meta,
        @Nullable String description,
        @Nullable String blurhash
) {

    record Meta(
            MetaValue original,
            MetaValue small,
            Point focus
    ) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ImageGeometry.class),
            @JsonSubTypes.Type(value = VideoMetadata.class)
    })
    interface MetaValue {
        Integer width();

        Integer height();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ImageGeometry(
            Integer width,
            Integer height,
            String size,
            Double aspect) implements MetaValue {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VideoMetadata(
            Integer width,
            Integer height,
            String frameRate,
            Double duration,
            Long bitrate) implements MetaValue {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Point(Double x, Double y) {
    }

    public Attachment toGenericMediaAttachment() {
        var sources = new ArrayList<Attachment.Source>();
        MetaValue originalMeta = meta == null ? null : meta.original();
        if (originalMeta != null) {
            sources.add(new Attachment.Source(url(),
                    null,
                    originalMeta instanceof VideoMetadata video ? video.bitrate() : null,
                    originalMeta.width(),
                    originalMeta.height()));
        }
        return new Attachment(
                url, type, description, sources, null, null, null);
    }

    public List<String> mediaUrls() {
        return mediaUrls(true);
    }

    public List<String> mediaUrls(boolean includeVideo) {
        boolean video = "video".equalsIgnoreCase(type) ||
                "gifv".equalsIgnoreCase(type);
        return java.util.stream.Stream.of(
                        includeVideo || !video ? url : null,
                        previewUrl,
                        previewRemoteUrl,
                        includeVideo || !video ? remoteUrl : null)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    public List<String> videoUrls() {
        boolean video = "video".equalsIgnoreCase(type) ||
                "gifv".equalsIgnoreCase(type);
        if (!video) {
            return List.of();
        }
        return java.util.stream.Stream.of(url, remoteUrl)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
