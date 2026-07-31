package org.netpreserve.warcana;

import java.net.URI;
import java.util.Locale;

enum ArchivePlatform {
    MASTODON,
    BLUESKY;

    static ArchivePlatform parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown platform '" + value + "' (expected mastodon or bluesky)");
        }
    }

    static ArchivePlatform detect(String account) {
        if (account.startsWith("http://") || account.startsWith("https://")) {
            URI uri = URI.create(account);
            if (uri.getHost() != null &&
                    (uri.getHost().equalsIgnoreCase("bsky.app") ||
                            uri.getHost().equalsIgnoreCase("www.bsky.app")) &&
                    uri.getPath().startsWith("/profile/")) {
                return BLUESKY;
            }
            return MASTODON;
        }
        if (account.startsWith("did:")) {
            return BLUESKY;
        }
        String withoutLeadingAt = account.replaceFirst("^@", "");
        if (withoutLeadingAt.contains("@")) {
            return MASTODON;
        }
        throw new IllegalArgumentException(
                "Cannot determine platform from account handle; " +
                        "use --platform mastodon or --platform bluesky");
    }

    String externalName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
