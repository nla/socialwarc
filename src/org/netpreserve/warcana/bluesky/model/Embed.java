package org.netpreserve.warcana.bluesky.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.jspecify.annotations.Nullable;
import org.netpreserve.warcana.common.model.Attachment;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.model.Site;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "$type",
        defaultImpl = Embed.UnknownEmbedView.class)
@JsonSubTypes({
        @JsonSubTypes.Type(Embed.ImagesView.class),
        @JsonSubTypes.Type(Embed.VideoView.class),
        @JsonSubTypes.Type(Embed.ExternalView.class),
        @JsonSubTypes.Type(Embed.RecordView.class),
        @JsonSubTypes.Type(Embed.RecordWithMediaView.class)
})
public sealed interface Embed {
    default List<Attachment> attachments() {
        return Collections.emptyList();
    }

    default List<String> mediaUrls(boolean includeVideo) {
        return Collections.emptyList();
    }

    default List<String> videoUrls() {
        var urls = new LinkedHashSet<>(mediaUrls(true));
        urls.removeAll(mediaUrls(false));
        return List.copyOf(urls);
    }

    default @Nullable Post quotedPost(Site site) {
        return null;
    }

    record AspectRatio(Integer width, Integer height) {
    }

    @JsonTypeName("app.bsky.embed.images#view")
    record ImagesView(
            List<Image> images) implements Embed {

        @Override
        public List<Attachment> attachments() {
            if (images == null) {
                return Collections.emptyList();
            }
            var attachments = new ArrayList<Attachment>();
            for (Image image : images) {
                var sources = new ArrayList<Attachment.Source>();
                if (image.fullsize() != null) {
                    sources.add(new Attachment.Source(
                            image.fullsize(),
                            null,
                            null,
                            image.aspectRatio() == null
                                    ? null
                                    : image.aspectRatio().width(),
                            image.aspectRatio() == null
                                    ? null
                                    : image.aspectRatio().height()));
                }
                if (image.thumb() != null) {
                    sources.add(new Attachment.Source(
                            image.thumb(), null, null, null, null));
                }
                attachments.add(new Attachment(
                        image.fullsize() == null
                                ? image.thumb()
                                : image.fullsize(),
                        "image",
                        image.alt(),
                        sources,
                        null,
                        null,
                        null));
            }
            return List.copyOf(attachments);
        }

        @Override
        public List<String> mediaUrls(boolean includeVideo) {
            if (images == null) {
                return Collections.emptyList();
            }
            var urls = new LinkedHashSet<String>();
            for (Image image : images) {
                addUrl(urls, image.fullsize());
                addUrl(urls, image.thumb());
            }
            return List.copyOf(urls);
        }

        public record Image(
                String thumb,
                String fullsize,
                String alt,
                AspectRatio aspectRatio) {
        }
    }

    @JsonTypeName("app.bsky.embed.video#view")
    record VideoView(
            String cid,
            String playlist,
            String thumbnail,
            String alt,
            AspectRatio aspectRatio) implements Embed {

        @Override
        public List<Attachment> attachments() {
            return List.of(new Attachment(
                    playlist,
                    "video",
                    alt,
                    playlist == null
                            ? Collections.emptyList()
                            : List.of(new Attachment.Source(
                                    playlist,
                                    "application/vnd.apple.mpegurl",
                                    null,
                                    aspectRatio == null
                                            ? null
                                            : aspectRatio.width(),
                                    aspectRatio == null
                                            ? null
                                            : aspectRatio.height())),
                    null,
                    null,
                    null));
        }

        @Override
        public List<String> mediaUrls(boolean includeVideo) {
            var urls = new LinkedHashSet<String>();
            if (includeVideo) {
                addUrl(urls, playlist);
            }
            addUrl(urls, thumbnail);
            return List.copyOf(urls);
        }
    }

    @JsonTypeName("app.bsky.embed.external#view")
    record ExternalView(
            External external) implements Embed {

        @Override
        public List<Attachment> attachments() {
            if (external == null) {
                return Collections.emptyList();
            }
            return List.of(new Attachment(
                    external.uri(),
                    "link",
                    null,
                    Collections.emptyList(),
                    external.title(),
                    external.description(),
                    external.thumb()));
        }

        @Override
        public List<String> mediaUrls(boolean includeVideo) {
            return external == null ||
                    external.thumb() == null ||
                    external.thumb().isBlank()
                    ? Collections.emptyList()
                    : List.of(external.thumb());
        }

        public record External(
                String uri,
                String title,
                String description,
                String thumb) {
        }
    }

    @JsonTypeName("app.bsky.embed.record#view")
    record RecordView(
            RecordViewValue record) implements Embed {

        @Override
        public List<String> mediaUrls(boolean includeVideo) {
            return record == null
                    ? Collections.emptyList()
                    : record.mediaUrls(includeVideo);
        }

        @Override
        public @Nullable Post quotedPost(Site site) {
            return record == null ? null : record.quotedPost(site);
        }
    }

    @JsonTypeName("app.bsky.embed.recordWithMedia#view")
    record RecordWithMediaView(
            RecordViewWrapper record,
            Embed media) implements Embed {

        @Override
        public List<Attachment> attachments() {
            return media == null
                    ? Collections.emptyList()
                    : media.attachments();
        }

        @Override
        public List<String> mediaUrls(boolean includeVideo) {
            var urls = new LinkedHashSet<String>();
            if (media != null) {
                urls.addAll(media.mediaUrls(includeVideo));
            }
            if (record != null) {
                urls.addAll(record.mediaUrls(includeVideo));
            }
            return List.copyOf(urls);
        }

        @Override
        public @Nullable Post quotedPost(Site site) {
            return record == null ? null : record.quotedPost(site);
        }
    }

    final class UnknownEmbedView implements Embed {
        private static final Set<String> MEDIA_FIELDS = Set.of(
                "avatar", "banner", "fullsize", "playlist",
                "thumb", "thumbnail");

        private final Map<String, JsonNode> fields = new LinkedHashMap<>();

        @JsonAnySetter
        public void set(String name, JsonNode value) {
            fields.put(name, value);
        }

        @JsonAnyGetter
        public Map<String, JsonNode> fields() {
            return fields;
        }

        @Override
        public List<String> mediaUrls(boolean includeVideo) {
            var urls = new LinkedHashSet<String>();
            collectMedia(fields, urls, includeVideo);
            return List.copyOf(urls);
        }

        private static void collectMedia(
                Map<String, JsonNode> fields,
                LinkedHashSet<String> urls,
                boolean includeVideo) {
            for (var field : fields.entrySet()) {
                collectMedia(
                        field.getKey(), field.getValue(), urls, includeVideo);
            }
        }

        private static void collectMedia(
                String name,
                JsonNode value,
                LinkedHashSet<String> urls,
                boolean includeVideo) {
            if (MEDIA_FIELDS.contains(name) &&
                    (includeVideo || !"playlist".equals(name)) &&
                    value != null &&
                    value.isString()) {
                addUrl(urls, value.stringValue());
            } else {
                collectMedia(value, urls, includeVideo);
            }
        }

        private static void collectMedia(
                JsonNode node,
                LinkedHashSet<String> urls,
                boolean includeVideo) {
            if (node == null || node.isNull()) {
                return;
            }
            if (node.isArray()) {
                for (JsonNode child : node) {
                    collectMedia(child, urls, includeVideo);
                }
            } else if (node.isObject()) {
                for (var property : node.properties()) {
                    collectMedia(
                            property.getKey(),
                            property.getValue(),
                            urls,
                            includeVideo);
                }
            }
        }
    }

    /**
     * The AppView sometimes omits $type from this nested wrapper even though
     * the same shape is polymorphic when used as a top-level embed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecordViewWrapper(RecordViewValue record) {
        List<String> mediaUrls(boolean includeVideo) {
            return record == null
                    ? Collections.emptyList()
                    : record.mediaUrls(includeVideo);
        }

        @Nullable Post quotedPost(Site site) {
            return record == null ? null : record.quotedPost(site);
        }
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "$type",
            defaultImpl = UnknownRecordView.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(ViewRecord.class)
    })
    sealed interface RecordViewValue {
        default List<String> mediaUrls(boolean includeVideo) {
            return Collections.emptyList();
        }

        default @Nullable Post quotedPost(Site site) {
            return null;
        }
    }

    @JsonTypeName("app.bsky.embed.record#viewRecord")
    record ViewRecord(
            String uri,
            String cid,
            Author author,
            PostRecord value,
            List<Embed> embeds,
            long replyCount,
            long repostCount,
            long likeCount,
            long quoteCount,
            Instant indexedAt) implements RecordViewValue {

        @Override
        public List<String> mediaUrls(boolean includeVideo) {
            var urls = new LinkedHashSet<String>();
            if (author != null) {
                addUrl(urls, author.avatar());
                addUrl(urls, author.banner());
            }
            if (embeds != null) {
                for (Embed embed : embeds) {
                    if (embed != null) {
                        urls.addAll(embed.mediaUrls(includeVideo));
                    }
                }
            }
            return List.copyOf(urls);
        }

        @Override
        public @Nullable Post quotedPost(Site site) {
            if (author == null ||
                    value == null ||
                    value.createdAt() == null) {
                return null;
            }
            List<Attachment> attachments = embeds == null
                    ? Collections.emptyList()
                    : embeds.stream()
                            .filter(embed -> embed != null)
                            .flatMap(embed -> embed.attachments().stream())
                            .toList();
            Post nestedQuote = embeds == null
                    ? null
                    : embeds.stream()
                            .filter(embed -> embed != null)
                            .map(embed -> embed.quotedPost(site))
                            .filter(post -> post != null)
                            .findFirst()
                            .orElse(null);
            return new Post(
                    "https://bsky.app/profile/" + author.handle() +
                            "/post/" + recordKey(uri),
                    value.createdAt(),
                    author.toGenericAccount(),
                    site,
                    null,
                    value.mentions(),
                    value.richTextHtml(),
                    attachments,
                    null,
                    nestedQuote,
                    replyCount,
                    repostCount,
                    quoteCount,
                    likeCount,
                    uri,
                    null,
                    null);
        }
    }

    final class UnknownRecordView implements RecordViewValue {
        private String uri;
        private final Map<String, JsonNode> fields = new LinkedHashMap<>();

        public String uri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        @JsonAnySetter
        public void set(String name, JsonNode value) {
            fields.put(name, value);
        }

        @JsonAnyGetter
        public Map<String, JsonNode> fields() {
            return fields;
        }
    }

    private static String recordKey(String uri) {
        if (uri == null) {
            return "";
        }
        int slash = uri.lastIndexOf('/');
        return slash < 0 ? uri : uri.substring(slash + 1);
    }

    private static void addUrl(LinkedHashSet<String> urls, String value) {
        if (value != null && !value.isBlank()) {
            urls.add(value);
        }
    }
}
