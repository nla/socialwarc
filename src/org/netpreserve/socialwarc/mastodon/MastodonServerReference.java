package org.netpreserve.socialwarc.mastodon;

import java.net.URI;
import java.util.Locale;

/** A Mastodon server whose local public timeline should be queried. */
public record MastodonServerReference(String server) {
    public static MastodonServerReference parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Server must not be blank");
        }
        String input = value.trim();
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            input = "https://" + input;
        }
        URI uri = URI.create(input);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Server must include a host");
        }
        if (!uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
            throw new IllegalArgumentException("Server URL must not include a path");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null ||
                uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Server URL must not include credentials, a query, or a fragment");
        }
        String authority = uri.getPort() < 0
                ? uri.getHost().toLowerCase(Locale.ROOT)
                : uri.getHost().toLowerCase(Locale.ROOT) + ":" + uri.getPort();
        return new MastodonServerReference(uri.getScheme() + "://" + authority);
    }

    public String defaultFilename() {
        return URI.create(server).getAuthority()
                .replaceAll("[^A-Za-z0-9._-]", "_") + "-local.warc";
    }
}
