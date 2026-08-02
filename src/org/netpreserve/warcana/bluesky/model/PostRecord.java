package org.netpreserve.warcana.bluesky.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

public record PostRecord(
        @JsonProperty("$type") String type,
        String text,
        Instant createdAt,
        @Nullable List<Facet> facets,
        @Nullable JsonNode embed) {

    public List<String> mentions() {
        if (facets == null) {
            return Collections.emptyList();
        }
        return facets.stream()
                .filter(facet -> facet != null && facet.features() != null)
                .flatMap(facet -> facet.features().stream())
                .filter(feature -> feature != null)
                .filter(feature -> "app.bsky.richtext.facet#mention".equals(feature.type()))
                .map(Feature::did)
                .filter(did -> did != null)
                .distinct()
                .toList();
    }

    public String richTextHtml() {
        if (text == null || text.isEmpty()) {
            return "";
        }

        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        var document = Document.createShell("");
        document.outputSettings().prettyPrint(false);
        Element content = document.body();
        int position = 0;

        for (Facet facet : sortedFacets()) {
            Index index = facet.index();
            if (!validRange(utf8, index) || index.byteStart() < position) {
                continue;
            }
            String href = href(facet);
            if (href == null) {
                continue;
            }

            appendText(content, utf8, position, index.byteStart());
            Element link = content.appendElement("a").attr("href", href);
            appendText(link, utf8, index.byteStart(), index.byteEnd());
            position = index.byteEnd();
        }

        appendText(content, utf8, position, utf8.length);
        return content.html();
    }

    private List<Facet> sortedFacets() {
        if (facets == null || facets.isEmpty()) {
            return Collections.emptyList();
        }
        var sorted = new ArrayList<>(facets);
        sorted.removeIf(facet -> facet == null || facet.index() == null);
        sorted.sort(Comparator.comparingInt(facet -> facet.index().byteStart()));
        return sorted;
    }

    private static boolean validRange(byte[] utf8, Index index) {
        return index != null &&
                index.byteStart() >= 0 &&
                index.byteStart() < index.byteEnd() &&
                index.byteEnd() <= utf8.length &&
                isCharacterBoundary(utf8, index.byteStart()) &&
                isCharacterBoundary(utf8, index.byteEnd());
    }

    private static boolean isCharacterBoundary(byte[] utf8, int offset) {
        return offset == utf8.length || (utf8[offset] & 0xc0) != 0x80;
    }

    private static @Nullable String href(Facet facet) {
        if (facet.features() == null) {
            return null;
        }
        for (Feature feature : facet.features()) {
            if (feature == null || feature.type() == null) {
                continue;
            }
            String href = switch (feature.type()) {
                case "app.bsky.richtext.facet#link" ->
                        httpUrl(feature.uri());
                case "app.bsky.richtext.facet#mention" ->
                        profileUrl(feature.did());
                case "app.bsky.richtext.facet#tag" ->
                        hashtagUrl(feature.tag());
                default -> null;
            };
            if (href != null) {
                return href;
            }
        }
        return null;
    }

    private static @Nullable String httpUrl(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) ||
                    "https".equalsIgnoreCase(uri.getScheme()))
                    ? uri.toASCIIString()
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String profileUrl(@Nullable String did) {
        return did == null || did.isBlank()
                ? null
                : encodedBlueskyPath("profile", did);
    }

    private static @Nullable String hashtagUrl(@Nullable String tag) {
        return tag == null || tag.isBlank()
                ? null
                : encodedBlueskyPath("hashtag", tag);
    }

    private static @Nullable String encodedBlueskyPath(
            String section, String value) {
        try {
            return new URI("https", "bsky.app", "/" + section + "/" + value,
                    null).toASCIIString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static void appendText(
            Element element, byte[] utf8, int start, int end) {
        String value = new String(
                utf8, start, end - start, StandardCharsets.UTF_8);
        String[] lines = value.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                element.appendElement("br");
            }
            element.appendText(lines[i]);
        }
    }

    public record Facet(
            @Nullable Index index,
            @Nullable List<Feature> features) {
    }

    public record Index(int byteStart, int byteEnd) {
    }

    public record Feature(
            @JsonProperty("$type") String type,
            @Nullable String did,
            @Nullable String uri,
            @Nullable String tag) {
    }
}
