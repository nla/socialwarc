package org.netpreserve.warcana.twitter;

import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.common.ExtractedPost;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.WarcResponseExtractor;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.twitter.model.TimelineV2;
import org.netpreserve.warcana.twitter.model.Tweet;
import org.netpreserve.warcana.twitter.model.User;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Extracts posts from legacy Twitter web API timeline WARCs. */
public final class TwitterWarcResponseExtractor
        implements WarcResponseExtractor {
    @Override
    public String platform() {
        return "twitter";
    }

    @Override
    public boolean supports(WarcResponse response) {
        if (!response.contentType().base().equals(MediaType.HTTP)) {
            return false;
        }
        String host = response.targetURI().getHost();
        String path = response.targetURI().getPath();
        if (host == null || path == null ||
                !(host.equalsIgnoreCase("twitter.com") ||
                        host.endsWith(".twitter.com") ||
                        host.equalsIgnoreCase("x.com") ||
                        host.endsWith(".x.com"))) {
            return false;
        }
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.contains("adaptive.json") ||
                normalized.contains("/timeline/") ||
                normalized.contains("usertweets") ||
                normalized.contains("searchtimeline") ||
                normalized.endsWith("/statuses/user_timeline.json");
    }

    @Override
    public List<ExtractedPost> extract(WarcResponse response)
            throws IOException {
        var http = response.http();
        if (http.status() < 200 || http.status() >= 300) {
            return List.of();
        }
        try {
            JsonNode root = SocialJson.mapper.readTree(
                    http.bodyDecoded().stream());
            var posts = new ArrayList<Post>();
            extractGlobalObjects(root, posts);
            extractGraphqlTweets(root, posts);
            var extracted = new ArrayList<ExtractedPost>();
            Set<String> seen = new LinkedHashSet<>();
            for (Post post : posts) {
                if (post == null || post.id() == null) {
                    continue;
                }
                String identity = post.url() == null
                        ? "twitter:" + post.id()
                        : post.url();
                if (seen.add(identity)) {
                    extracted.add(new ExtractedPost(identity, post));
                }
            }
            return List.copyOf(extracted);
        } catch (Exception e) {
            throw new IOException(
                    "Invalid Twitter timeline response at WARC offset " +
                            response.position() + " (" + response.target() + ")",
                    e);
        }
    }

    private static void extractGlobalObjects(
            JsonNode root, List<Post> posts) {
        JsonNode global = root.path("globalObjects");
        JsonNode userNodes = global.path("users");
        JsonNode tweetNodes = global.path("tweets");
        if (!userNodes.isObject() || !tweetNodes.isObject()) {
            return;
        }
        Map<String, User> users = new LinkedHashMap<>();
        for (var property : userNodes.properties()) {
            User user = SocialJson.mapper.treeToValue(
                    property.getValue(), User.class);
            users.put(property.getKey(), user);
        }
        for (var property : tweetNodes.properties()) {
            Tweet tweet = SocialJson.mapper.treeToValue(
                    property.getValue(), Tweet.class);
            posts.add(tweet.toPost(users));
        }
    }

    private static void extractGraphqlTweets(
            JsonNode node, List<Post> posts) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if ("Tweet".equals(node.path("__typename").asText()) &&
                    node.has("legacy") && node.has("core") &&
                    node.has("rest_id")) {
                TimelineV2.TweetV2 tweet = SocialJson.mapper.treeToValue(
                        node, TimelineV2.TweetV2.class);
                posts.add(tweet.toPost());
                return;
            }
            for (var property : node.properties()) {
                extractGraphqlTweets(property.getValue(), posts);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                extractGraphqlTweets(child, posts);
            }
        }
    }
}
