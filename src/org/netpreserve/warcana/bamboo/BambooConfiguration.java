package org.netpreserve.warcana.bamboo;

import org.netpreserve.warcana.web.OidcConfiguration;

import java.net.URI;
import java.util.List;

public record BambooConfiguration(
        URI baseUrl,
        Long ingestSeriesId,
        List<Long> indexSeriesIds,
        OidcConfiguration oidc) {
    public BambooConfiguration {
        if (baseUrl == null || !baseUrl.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Bamboo base URL must be an absolute URL");
        }
        if (ingestSeriesId != null && ingestSeriesId <= 0) {
            throw new IllegalArgumentException(
                    "Bamboo ingest series ID must be positive");
        }
        indexSeriesIds = indexSeriesIds == null
                ? List.of()
                : List.copyOf(indexSeriesIds);
        if (indexSeriesIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(
                    "Bamboo index series IDs must be positive");
        }
        if (ingestSeriesId == null && indexSeriesIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bamboo requires an ingest or index crawl series");
        }
        String value = baseUrl.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        baseUrl = URI.create(value);
    }

    public boolean ingestEnabled() {
        return ingestSeriesId != null;
    }

    public URI resolve(String path) {
        return URI.create(baseUrl + (path.startsWith("/") ? path : "/" + path));
    }
}
