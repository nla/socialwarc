package org.netpreserve.warcana.bluesky.model;

import org.jspecify.annotations.Nullable;
import org.netpreserve.warcana.common.model.Attachment;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.model.Site;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public record PostView(
        String uri,
        String cid,
        Author author,
        PostRecord record,
        @Nullable JsonNode embed,
        long replyCount,
        long repostCount,
        long likeCount,
        long quoteCount,
        Instant indexedAt) {

    public Post toGenericPost(FeedItem.ReplyContext reply, Site site) {
        String to = reply == null || reply.parent() == null || reply.parent().author() == null
                ? null
                : reply.parent().author().handle();
        return new Post(
                webUrl(),
                record.createdAt(),
                author.toGenericAccount(),
                site,
                to,
                record.mentions(),
                record.text(),
                attachments(embed),
                null,
                quotedPost(embed, site),
                replyCount,
                repostCount,
                quoteCount,
                likeCount);
    }

    public String webUrl() {
        int slash = uri.lastIndexOf('/');
        String recordKey = slash < 0 ? uri : uri.substring(slash + 1);
        return "https://bsky.app/profile/" + author.handle() + "/post/" + recordKey;
    }

    public List<String> mediaUrls() {
        return mediaUrls(true);
    }

    public List<String> mediaUrls(boolean includeVideo) {
        var urls = new LinkedHashSet<String>();
        collectMediaUrls(embed, urls, includeVideo);
        if (author != null) {
            addUrl(urls, author.avatar());
            addUrl(urls, author.banner());
        }
        return List.copyOf(urls);
    }

    private static void collectMediaUrls(
            JsonNode node, LinkedHashSet<String> urls, boolean includeVideo) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectMediaUrls(child, urls, includeVideo);
            }
            return;
        }

        String type = node.path("$type").asText();
        switch (type) {
            case "app.bsky.embed.images#view" -> {
                for (JsonNode image : node.path("images")) {
                    addUrl(urls, textOrNull(image, "fullsize"));
                    addUrl(urls, textOrNull(image, "thumb"));
                }
            }
            case "app.bsky.embed.video#view" -> {
                if (includeVideo) {
                    addUrl(urls, textOrNull(node, "playlist"));
                }
                addUrl(urls, textOrNull(node, "thumbnail"));
            }
            case "app.bsky.embed.external#view" ->
                    addUrl(urls, textOrNull(node.path("external"), "thumb"));
            case "app.bsky.embed.recordWithMedia#view" -> {
                collectMediaUrls(node.get("media"), urls, includeVideo);
                collectMediaUrls(node.get("record"), urls, includeVideo);
            }
            case "app.bsky.embed.record#view" ->
                    collectMediaUrls(node.get("record"), urls, includeVideo);
            case "app.bsky.embed.record#viewRecord" -> {
                JsonNode authorNode = node.path("author");
                addUrl(urls, textOrNull(authorNode, "avatar"));
                addUrl(urls, textOrNull(authorNode, "banner"));
                collectMediaUrls(node.get("embeds"), urls, includeVideo);
                collectMediaUrls(node.path("value").get("embed"), urls, includeVideo);
            }
            default -> {
                // Unknown embed types may wrap a standard view under these fields.
                collectMediaUrls(node.get("media"), urls, includeVideo);
                collectMediaUrls(node.get("record"), urls, includeVideo);
                collectMediaUrls(node.get("embeds"), urls, includeVideo);
            }
        }
    }

    private static void addUrl(LinkedHashSet<String> urls, String value) {
        if (value != null && !value.isBlank()) {
            urls.add(value);
        }
    }

    private static List<Attachment> attachments(JsonNode embed) {
        if (embed == null || embed.isNull()) {
            return Collections.emptyList();
        }
        String type = embed.path("$type").asText();
        if ("app.bsky.embed.recordWithMedia#view".equals(type)) {
            return attachments(embed.get("media"));
        }

        ArrayList<Attachment> attachments = new ArrayList<>();
        if ("app.bsky.embed.images#view".equals(type)) {
            for (JsonNode image : embed.path("images")) {
                String fullsize = textOrNull(image, "fullsize");
                String thumbnail = textOrNull(image, "thumb");
                var sources = new ArrayList<Attachment.Source>();
                if (fullsize != null) {
                    sources.add(new Attachment.Source(
                            fullsize, null, null,
                            integerOrNull(image.path("aspectRatio"), "width"),
                            integerOrNull(image.path("aspectRatio"), "height")));
                }
                if (thumbnail != null) {
                    sources.add(new Attachment.Source(thumbnail, null, null, null, null));
                }
                attachments.add(new Attachment(
                        fullsize == null ? thumbnail : fullsize,
                        "image",
                        textOrNull(image, "alt"),
                        sources));
            }
        } else if ("app.bsky.embed.video#view".equals(type)) {
            String playlist = textOrNull(embed, "playlist");
            attachments.add(new Attachment(
                    playlist,
                    "video",
                    textOrNull(embed, "alt"),
                    playlist == null ? Collections.emptyList() : List.of(
                            new Attachment.Source(
                                    playlist,
                                    "application/vnd.apple.mpegurl",
                                    null,
                                    integerOrNull(embed.path("aspectRatio"), "width"),
                                    integerOrNull(embed.path("aspectRatio"), "height")))));
        } else if ("app.bsky.embed.external#view".equals(type)) {
            JsonNode external = embed.path("external");
            String uri = textOrNull(external, "uri");
            attachments.add(new Attachment(
                    uri,
                    "link",
                    textOrNull(external, "description"),
                    Collections.emptyList()));
        }
        return attachments;
    }

    private static Post quotedPost(JsonNode embed, Site site) {
        if (embed == null || embed.isNull()) {
            return null;
        }
        String type = embed.path("$type").asText();
        if ("app.bsky.embed.recordWithMedia#view".equals(type)) {
            return quotedPost(embed.get("record"), site);
        }
        if ("app.bsky.embed.record#view".equals(type)) {
            return quotedRecord(embed.get("record"), site);
        }
        return null;
    }

    private static Post quotedRecord(JsonNode view, Site site) {
        if (view == null ||
                !"app.bsky.embed.record#viewRecord".equals(view.path("$type").asText())) {
            return null;
        }
        JsonNode authorNode = view.path("author");
        JsonNode value = view.path("value");
        String handle = authorNode.path("handle").asText();
        String quotedUri = view.path("uri").asText();
        int slash = quotedUri.lastIndexOf('/');
        String recordKey = slash < 0 ? quotedUri : quotedUri.substring(slash + 1);
        var account = new org.netpreserve.warcana.common.model.Account(
                handle,
                textOrNull(authorNode, "displayName"),
                textOrNull(authorNode, "avatar"),
                null);
        Instant createdAt;
        try {
            createdAt = Instant.parse(value.path("createdAt").asText());
        } catch (RuntimeException e) {
            return null;
        }
        return new Post(
                "https://bsky.app/profile/" + handle + "/post/" + recordKey,
                createdAt,
                account,
                site,
                null,
                Collections.emptyList(),
                value.path("text").asText(),
                attachments(view.get("embeds")),
                null,
                null,
                longOrZero(view, "replyCount"),
                longOrZero(view, "repostCount"),
                longOrZero(view, "quoteCount"),
                longOrZero(view, "likeCount"));
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static long longOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? 0 : value.asLong();
    }
}
