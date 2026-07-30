package org.netpreserve.warcana.bluesky;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A Bluesky handle or DID accepted by the AT Protocol APIs.
 */
public record BlueskyAccountReference(String actor) {
    private static final Pattern HANDLE = Pattern.compile(
            "^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
                    "[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern DID = Pattern.compile("^did:[a-z0-9]+:[A-Za-z0-9._:%-]+$");

    public static BlueskyAccountReference parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Account must not be blank");
        }

        String actor = value.trim();
        if (actor.startsWith("http://") || actor.startsWith("https://")) {
            actor = actorFromProfileUrl(URI.create(actor));
        } else {
            actor = actor.replaceFirst("^@", "");
        }
        boolean isDid = DID.matcher(actor).matches();
        if (!isDid) {
            actor = actor.toLowerCase(Locale.ROOT);
        }
        if (!isDid && !HANDLE.matcher(actor).matches()) {
            throw new IllegalArgumentException(
                    "Account must look like @user.bsky.social or https://bsky.app/profile/user.bsky.social");
        }
        return new BlueskyAccountReference(actor);
    }

    private static String actorFromProfileUrl(URI uri) {
        String host = uri.getHost();
        if (host == null ||
                (!host.equalsIgnoreCase("bsky.app") && !host.equalsIgnoreCase("www.bsky.app"))) {
            throw new IllegalArgumentException("Bluesky profile URL must use bsky.app");
        }
        String[] segments = uri.getPath().split("/");
        if (segments.length != 3 || !"profile".equals(segments[1]) || segments[2].isBlank()) {
            throw new IllegalArgumentException(
                    "Bluesky profile URL must look like https://bsky.app/profile/user.bsky.social");
        }
        return segments[2];
    }

    public String defaultFilename() {
        return actor.replaceAll("[^A-Za-z0-9._-]", "_") + ".warc";
    }
}
