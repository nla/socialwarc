package org.netpreserve.warcana.bluesky.model;

import org.jspecify.annotations.Nullable;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.model.Site;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public record PostView(
        String uri,
        String cid,
        Author author,
        PostRecord record,
        @Nullable Embed embed,
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
                embed == null
                        ? Collections.emptyList()
                        : embed.attachments(),
                null,
                embed == null ? null : embed.quotedPost(site),
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
        if (embed != null) {
            urls.addAll(embed.mediaUrls(includeVideo));
        }
        if (author != null) {
            addUrl(urls, author.avatar());
            addUrl(urls, author.banner());
        }
        return List.copyOf(urls);
    }

    public List<String> videoUrls() {
        return embed == null ? List.of() : embed.videoUrls();
    }

    private static void addUrl(LinkedHashSet<String> urls, String value) {
        if (value != null && !value.isBlank()) {
            urls.add(value);
        }
    }
}
