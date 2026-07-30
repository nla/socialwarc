package org.netpreserve.warcana.mastodon;

import java.net.URI;
import java.util.Locale;

/**
 * A Mastodon account together with the server whose API should be queried.
 */
public record MastodonAccountReference(String server, String account) {
    public static MastodonAccountReference parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Account must not be blank");
        }

        String input = value.trim();
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return fromUrl(URI.create(input));
        }

        input = input.replaceFirst("^@", "");
        int separator = input.lastIndexOf('@');
        if (separator <= 0 || separator == input.length() - 1) {
            throw new IllegalArgumentException(
                    "Account must look like @user@server or https://server/@user");
        }

        String username = input.substring(0, separator);
        String host = input.substring(separator + 1).toLowerCase(Locale.ROOT);
        return new MastodonAccountReference("https://" + host, username);
    }

    private static MastodonAccountReference fromUrl(URI uri) {
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Account URL must include a host");
        }

        String[] segments = uri.getPath().split("/");
        String username = null;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank()) {
                username = segments[i].replaceFirst("^@", "");
                break;
            }
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Account URL must include a username");
        }

        String authority = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        return new MastodonAccountReference(uri.getScheme() + "://" + authority, username);
    }

    public String defaultFilename() {
        String host = URI.create(server).getAuthority();
        return (account + "@" + host).replaceAll("[^A-Za-z0-9@._-]", "_") + ".warc";
    }
}
