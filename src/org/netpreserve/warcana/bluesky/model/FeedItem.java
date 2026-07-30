package org.netpreserve.warcana.bluesky.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.model.Site;

import java.time.Instant;
import java.util.Collections;

public record FeedItem(
        PostView post,
        @Nullable ReplyContext reply,
        @Nullable Reason reason) {

    private static final Site BLUESKY = new Site("Bluesky");

    public Post toGenericPost() {
        Post original = post.toGenericPost(reply, BLUESKY);
        if (reason == null || reason.by() == null ||
                !"app.bsky.feed.defs#reasonRepost".equals(reason.type())) {
            return original;
        }
        return new Post(
                original.url(),
                reason.indexedAt(),
                reason.by().toGenericAccount(),
                BLUESKY,
                null,
                Collections.emptyList(),
                "",
                Collections.emptyList(),
                original,
                null,
                0L,
                0L,
                0L,
                0L);
    }

    public String identity() {
        if (reason != null && reason.by() != null) {
            return reason.by().did() + ":" + post.uri();
        }
        return post.uri();
    }

    public record Reason(
            @JsonProperty("$type") String type,
            Author by,
            Instant indexedAt) {
    }

    public record ReplyContext(ReplyPost root, ReplyPost parent) {
    }

    public record ReplyPost(String uri, Author author) {
    }
}
