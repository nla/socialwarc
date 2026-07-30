package org.netpreserve.warcana.mastodon.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

// https://docs.joinmastodon.org/entities/Account/
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Account(
        @NonNull String id,
        @NonNull String username,
        @NonNull String acct,
        @NonNull String url,
        String uri,
        @NonNull String displayName,
        @NonNull String note,
        @NonNull String avatar,
        @NonNull String avatarStatic,
        @NonNull String header,
        @NonNull String headerStatic,
        boolean locked,
        @Nullable List<Field> fields,
        @Nullable List<CustomEmoji> emojis,
        @Nullable List<Object> roles,
        boolean noindex,
        boolean bot,
        boolean group,
        boolean discoverable,
        @Nullable Account moved,
        @JsonInclude(NON_DEFAULT) boolean suspended,
        @JsonInclude(NON_DEFAULT) boolean limited,
        @Nullable Instant createdAt,
        @Nullable LocalDate lastStatusAt,
        long statusesCount,
        long followersCount,
        long followingCount,
        @Nullable Boolean indexable,
        @Nullable Boolean hide_collections) {

    public org.netpreserve.warcana.common.model.Account toGenericAccount() {
        return new org.netpreserve.warcana.common.model.Account(
                username,
                displayName,
                avatar,
                header);
    }

    public List<String> mediaUrls() {
        var urls = new LinkedHashSet<String>();
        addUrl(urls, avatar);
        addUrl(urls, avatarStatic);
        addUrl(urls, header);
        addUrl(urls, headerStatic);
        if (emojis != null) {
            for (CustomEmoji emoji : emojis) {
                addUrl(urls, emoji.url());
                addUrl(urls, emoji.staticUrl());
            }
        }
        return List.copyOf(urls);
    }

    private static void addUrl(LinkedHashSet<String> urls, String value) {
        if (value != null && !value.isBlank()) {
            urls.add(value);
        }
    }

    // https://docs.joinmastodon.org/entities/Account/#Field
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Field(
            @NonNull String name,
            @NonNull String value,
            @Nullable Instant verifiedAt
    ) {
    }
}
