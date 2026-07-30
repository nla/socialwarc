package org.netpreserve.warcana.bluesky.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
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
                .flatMap(facet -> facet.features().stream())
                .filter(feature -> "app.bsky.richtext.facet#mention".equals(feature.type()))
                .map(Feature::did)
                .filter(did -> did != null)
                .distinct()
                .toList();
    }

    public record Facet(List<Feature> features) {
    }

    public record Feature(
            @JsonProperty("$type") String type,
            @Nullable String did,
            @Nullable String uri,
            @Nullable String tag) {
    }
}
