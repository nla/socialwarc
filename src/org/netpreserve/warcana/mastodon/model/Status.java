package org.netpreserve.warcana.mastodon.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.model.Site;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
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
        return new Post(
                url,
                createdAt,
                account.toGenericAccount(),
                site,
                inReplyToAccountId,
                mentions().stream().map(Mention::username).toList(),
                content,
                mediaAttachments.stream().map(MediaAttachment::toGenericMediaAttachment).toList(),
                repost,
                null,
                repliesCount,
                reblogsCount,
                null,
                favouritesCount
        );
    }
}
