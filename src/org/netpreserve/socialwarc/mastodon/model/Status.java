package org.netpreserve.socialwarc.mastodon.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.netpreserve.socialwarc.common.model.Post;
import org.netpreserve.socialwarc.common.model.Site;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

// https://docs.joinmastodon.org/entities/Status/
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Status(
        @NonNull String id,
        @NonNull String uri,
        @NonNull Instant createdAt,
        @NonNull Account account,
        @NonNull String content,
        @NonNull Visibility visibility,
        boolean sensitive,
        @NonNull String spoilerText,
        @NonNull List<MediaAttachment> mediaAttachments,
        @Nullable Application application,
        @NonNull List<Mention> mentions,
        @NonNull List<Tag> tags,
        @NonNull List<CustomEmoji> emojis,
        long reblogsCount,
        long favouritesCount,
        long repliesCount,
        @Nullable String url,
        @Nullable String inReplyToId,
        @Nullable String inReplyToAccountId,
        @Nullable Status reblog,
        @Nullable Poll poll,
        @Nullable PreviewCard card,
        @Nullable String language,
        @Nullable String text,
        @Nullable Instant editedAt) {

    // https://docs.joinmastodon.org/entities/Status/#application
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Application(
            @NonNull String name,
            @Nullable String website
    ) {
    }

    // https://docs.joinmastodon.org/entities/Status/#Mention
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Mention(
            @NonNull String id,
            @NonNull String username,
            @NonNull String url,
            @NonNull String acct
    ) {
    }

    // https://docs.joinmastodon.org/entities/Status/#Tag
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Tag(
            @NonNull String name,
            @NonNull String url
    ) {
    }

    // https://docs.joinmastodon.org/entities/Status/#visibility
    public enum Visibility {
        @JsonProperty("public") PUBLIC,
        @JsonProperty("unlisted") UNLISTED,
        @JsonProperty("private") PRIVATE,
        @JsonProperty("direct") DIRECT
    }

    public Post toGenericPost(Site site) {
        Post repost = reblog == null ? null : reblog.toGenericPost(site);
        var attachments = new ArrayList<>(mediaAttachments.stream()
                .map(MediaAttachment::toGenericMediaAttachment)
                .toList());
        if (card != null) {
            attachments.add(card.toGenericAttachment());
        }
        var replyAuthor = replyAuthor();
        Post.ThreadContext thread = inReplyToId == null
                ? null
                : new Post.ThreadContext(
                        null,
                        new Post.PostReference(
                                inReplyToId,
                                null,
                                replyAuthor));
        return new Post(
                url,
                createdAt,
                account.toGenericAccount(site),
                site,
                replyAuthor == null ? null : replyAuthor.username(),
                mentions().stream().map(Mention::username).toList(),
                content,
                List.copyOf(attachments),
                repost,
                null,
                repliesCount,
                reblogsCount,
                null,
                favouritesCount,
                id,
                editedAt,
                thread
        );
    }

    private org.netpreserve.socialwarc.common.model.Account replyAuthor() {
        if (inReplyToAccountId == null) {
            return null;
        }
        return mentions.stream()
                .filter(mention -> inReplyToAccountId.equals(mention.id()))
                .findFirst()
                .map(mention -> new org.netpreserve.socialwarc.common.model.Account(
                        mention.acct(), null, null, null))
                .orElse(null);
    }

    public List<String> mediaUrls() {
        return mediaUrls(true);
    }

    public List<String> mediaUrls(boolean includeVideo) {
        var urls = new LinkedHashSet<String>(account.mediaUrls());
        for (MediaAttachment attachment : mediaAttachments) {
            urls.addAll(attachment.mediaUrls(includeVideo));
        }
        for (CustomEmoji emoji : emojis) {
            addUrl(urls, emoji.url());
            addUrl(urls, emoji.staticUrl());
        }
        if (poll != null && poll.emojis() != null) {
            for (CustomEmoji emoji : poll.emojis()) {
                addUrl(urls, emoji.url());
                addUrl(urls, emoji.staticUrl());
            }
        }
        if (card != null) {
            addUrl(urls, card.image());
        }
        if (reblog != null) {
            urls.addAll(reblog.mediaUrls(includeVideo));
        }
        return List.copyOf(urls);
    }

    public List<String> videoUrls() {
        var urls = new LinkedHashSet<String>();
        for (MediaAttachment attachment : mediaAttachments) {
            urls.addAll(attachment.videoUrls());
        }
        if (reblog != null) {
            urls.addAll(reblog.videoUrls());
        }
        return List.copyOf(urls);
    }

    private static void addUrl(LinkedHashSet<String> urls, String value) {
        if (value != null && !value.isBlank()) {
            urls.add(value);
        }
    }
}
