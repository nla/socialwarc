package org.netpreserve.socialwarc.threads;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Entities;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.socialwarc.common.ExtractedPost;
import org.netpreserve.socialwarc.common.SocialJson;
import org.netpreserve.socialwarc.common.WarcResponseExtractor;
import org.netpreserve.socialwarc.common.model.Account;
import org.netpreserve.socialwarc.common.model.Attachment;
import org.netpreserve.socialwarc.common.model.Post;
import org.netpreserve.socialwarc.common.model.Site;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Extracts posts embedded in archived public Threads profile pages. */
public final class ThreadsWarcResponseExtractor
        implements WarcResponseExtractor {
    private static final Site THREADS = new Site("Threads");

    @Override
    public String platform() {
        return "threads";
    }

    @Override
    public boolean supports(WarcResponse response) {
        if (!response.contentType().base().equals(MediaType.HTTP)) {
            return false;
        }
        String host = response.targetURI().getHost();
        String path = response.targetURI().getPath();
        if (host == null || path == null || !isThreadsHost(host)) {
            return false;
        }
        return path.matches("^/@[^/]+(?:/post/[^/]+)?/?$");
    }

    private static boolean isThreadsHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("threads.com") ||
                normalized.equals("www.threads.com") ||
                normalized.equals("threads.net") ||
                normalized.equals("www.threads.net");
    }

    @Override
    public List<ExtractedPost> extract(WarcResponse response)
            throws IOException {
        var http = response.http();
        if (http.status() < 200 || http.status() >= 300) {
            return List.of();
        }
        try {
            var document = Jsoup.parse(
                    http.bodyDecoded().stream(), null,
                    response.target().toString());
            var extracted = new ArrayList<ExtractedPost>();
            Set<String> seen = new LinkedHashSet<>();
            for (var script : document.select("script[type=application/json]")) {
                String json = script.data();
                if (!json.contains("\"thread_items\"")) {
                    continue;
                }
                extractThreads(SocialJson.mapper.readTree(json), extracted, seen);
            }
            return List.copyOf(extracted);
        } catch (Exception e) {
            throw new IOException(
                    "Invalid Threads profile response at WARC offset " +
                            response.position() + " (" + response.target() + ")",
                    e);
        }
    }

    private static void extractThreads(
            JsonNode node, List<ExtractedPost> extracted, Set<String> seen) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode items = node.get("thread_items");
            if (items != null && items.isArray()) {
                extractThread(node, items, extracted, seen);
                return;
            }
            for (var property : node.properties()) {
                extractThreads(property.getValue(), extracted, seen);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                extractThreads(child, extracted, seen);
            }
        }
    }

    private static void extractThread(
            JsonNode thread, JsonNode items,
            List<ExtractedPost> extracted, Set<String> seen) {
        String rootId = text(thread, "id");
        JsonNode root = null;
        JsonNode previous = null;
        for (JsonNode item : items) {
            JsonNode media = item.path("post");
            if (!isPost(media)) {
                continue;
            }
            if (root == null) {
                root = media;
            }
            String identity = postUrl(media);
            if (identity != null && seen.add(identity)) {
                extracted.add(new ExtractedPost(
                        identity, toPost(media, rootId, root, previous)));
            }
            previous = media;
        }
    }

    private static Post toPost(
            JsonNode media, String rootId, JsonNode root, JsonNode previous) {
        JsonNode user = media.path("user");
        JsonNode info = media.path("text_post_app_info");
        Account author = toAccount(user);
        String id = firstText(media, "pk", "id");
        String to = text(info.path("reply_to_author"), "username");
        Post.ThreadContext context = null;
        if (info.path("is_reply").asBoolean(false)) {
            JsonNode parent = previous == null
                    ? info.path("reply_to_author") : previous;
            context = new Post.ThreadContext(
                    reference(rootId, root, author),
                    reference(firstText(parent, "pk", "id"), parent,
                            toAccount(parent.path("user").isObject()
                                    ? parent.path("user") : parent)));
        }
        return new Post(
                postUrl(media),
                epochSeconds(media.get("taken_at")),
                author,
                THREADS,
                to,
                mentions(info),
                content(media, info),
                attachments(media),
                nestedPost(info.path("share_info").path("reposted_post")),
                nestedPost(info.path("share_info").path("quoted_post")),
                number(info, "direct_reply_count"),
                number(info, "repost_count"),
                number(info, "quote_count"),
                number(media, "like_count"),
                id,
                null,
                context);
    }

    private static Post nestedPost(JsonNode node) {
        JsonNode media = findPost(node);
        return media == null ? null : toPost(media, null, null, null);
    }

    private static JsonNode findPost(JsonNode node) {
        if (isPost(node)) {
            return node;
        }
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            for (var property : node.properties()) {
                JsonNode found = findPost(property.getValue());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findPost(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isPost(JsonNode node) {
        return node != null && node.isObject() &&
                node.path("user").isObject() &&
                (node.hasNonNull("pk") || node.hasNonNull("id")) &&
                node.path("text_post_app_info").isObject();
    }

    private static Post.PostReference reference(
            String id, JsonNode media, Account fallbackAuthor) {
        if (id == null) {
            return null;
        }
        Account author = media != null && media.path("user").isObject()
                ? toAccount(media.path("user")) : fallbackAuthor;
        return new Post.PostReference(
                id, media == null ? null : postUrl(media), author);
    }

    private static Account toAccount(JsonNode user) {
        if (user == null || !user.isObject()) {
            return null;
        }
        return new Account(
                text(user, "username"),
                text(user, "full_name"),
                text(user, "profile_pic_url"),
                null);
    }

    private static String postUrl(JsonNode media) {
        String username = text(media.path("user"), "username");
        String code = text(media, "code");
        if (username == null || code == null) {
            return null;
        }
        return "https://www.threads.com/@" + username + "/post/" + code;
    }

    private static String content(JsonNode media, JsonNode info) {
        JsonNode fragments = info.path("text_fragments").path("fragments");
        if (!fragments.isArray()) {
            return lineBreaks(Entities.escape(
                    text(media.path("caption"), "text") == null
                            ? "" : text(media.path("caption"), "text")));
        }
        var html = new StringBuilder();
        for (JsonNode fragment : fragments) {
            String value = text(fragment, "plaintext");
            if (value == null) {
                continue;
            }
            String escaped = lineBreaks(Entities.escape(value));
            String href = text(fragment.path("link_fragment"), "uri");
            if (href == null && fragment.path("mention_fragment").isObject()) {
                String mentioned = text(fragment.path("mention_fragment")
                        .path("mentioned_user"), "username");
                if (mentioned != null) {
                    href = "https://www.threads.com/@" + mentioned;
                }
            }
            if (href == null) {
                html.append(escaped);
            } else {
                html.append("<a href=\"")
                        .append(Entities.escape(href))
                        .append("\">").append(escaped).append("</a>");
            }
        }
        return html.toString();
    }

    private static String lineBreaks(String value) {
        return value.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br>");
    }

    private static List<String> mentions(JsonNode info) {
        var mentions = new LinkedHashSet<String>();
        JsonNode fragments = info.path("text_fragments").path("fragments");
        if (fragments.isArray()) {
            for (JsonNode fragment : fragments) {
                String username = text(fragment.path("mention_fragment")
                        .path("mentioned_user"), "username");
                if (username != null) {
                    mentions.add(username);
                }
            }
        }
        return List.copyOf(mentions);
    }

    private static List<Attachment> attachments(JsonNode media) {
        JsonNode carousel = media.path("carousel_media");
        var attachments = new ArrayList<Attachment>();
        if (carousel.isArray() && !carousel.isEmpty()) {
            for (JsonNode item : carousel) {
                addAttachment(item, attachments);
            }
        } else {
            addAttachment(media, attachments);
        }
        return List.copyOf(attachments);
    }

    private static void addAttachment(
            JsonNode media, List<Attachment> attachments) {
        var imageSources = sources(media.path("image_versions2")
                .path("candidates"), null);
        var videoSources = sources(media.path("video_versions"), "video/mp4");
        String alt = text(media, "accessibility_caption");
        if (!videoSources.isEmpty()) {
            attachments.add(new Attachment(
                    videoSources.getFirst().url(),
                    "video",
                    alt,
                    videoSources,
                    null,
                    null,
                    imageSources.isEmpty() ? null : imageSources.getFirst().url()));
        } else if (!imageSources.isEmpty()) {
            attachments.add(new Attachment(
                    imageSources.getFirst().url(),
                    "image",
                    alt,
                    imageSources,
                    null,
                    null,
                    null));
        }
    }

    private static List<Attachment.Source> sources(
            JsonNode candidates, String contentType) {
        if (!candidates.isArray()) {
            return List.of();
        }
        var sources = new ArrayList<Attachment.Source>();
        var seen = new LinkedHashSet<String>();
        for (JsonNode candidate : candidates) {
            String url = text(candidate, "url");
            if (url != null && seen.add(url)) {
                sources.add(new Attachment.Source(
                        url,
                        contentType,
                        number(candidate, "bitrate"),
                        integer(candidate, "width"),
                        integer(candidate, "height")));
            }
        }
        return List.copyOf(sources);
    }

    private static Instant epochSeconds(JsonNode value) {
        return value == null || !value.canConvertToLong()
                ? null : Instant.ofEpochSecond(value.asLong());
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String name) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(name);
        return value == null || value.isNull() || !value.isValueNode()
                ? null : value.asText();
    }

    private static Long number(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || !value.canConvertToLong()
                ? null : value.asLong();
    }

    private static Integer integer(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || !value.canConvertToInt()
                ? null : value.asInt();
    }
}
