package org.netpreserve.warcana.bamboo;

import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.source.WarcSource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Enumerates immutable WARC objects in configured Bamboo crawl series. */
public final class BambooCatalog {
    private final BambooConfiguration configuration;
    private final BambooClient client;

    public BambooCatalog(BambooConfiguration configuration) {
        this.configuration = configuration;
        client = new BambooClient(configuration);
    }

    public List<WarcSource> sources() throws IOException {
        if (configuration.indexSeriesIds().isEmpty()) {
            return List.of();
        }
        try {
            Set<Long> crawlIds = crawlIds();
            var sources = new ArrayList<WarcSource>();
            for (long crawlId : crawlIds) {
                for (long warcId : warcIds(crawlId)) {
                    BambooWarcSource source = warc(warcId);
                    if (source.size() > 0) {
                        sources.add(source);
                    }
                }
            }
            return sources.stream()
                    .sorted(java.util.Comparator.comparing(WarcSource::id))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading Bamboo catalogue", e);
        }
    }

    private Set<Long> crawlIds() throws IOException, InterruptedException {
        Set<Long> selectedSeries = new HashSet<>(
                configuration.indexSeriesIds());
        var crawlIds = new HashSet<Long>();
        int page = 0;
        while (true) {
            HttpResponse<String> response = client.get(configuration.resolve(
                    "/api/crawls?size=1000&sort=id,asc&page=" + page));
            requireOk("listing Bamboo crawls", response);
            JsonNode root = SocialJson.mapper.readTree(response.body());
            JsonNode crawls = root.path("_embedded").path("crawls");
            if (crawls.isArray()) {
                for (JsonNode crawl : crawls) {
                    if (selectedSeries.contains(
                            crawl.path("crawlSeriesId").asLong())) {
                        crawlIds.add(crawl.path("id").asLong());
                    }
                }
            }
            int totalPages = root.path("page").path("totalPages").asInt(1);
            page++;
            if (page >= totalPages) {
                break;
            }
        }
        return crawlIds;
    }

    private List<Long> warcIds(long crawlId)
            throws IOException, InterruptedException {
        var ids = new ArrayList<Long>();
        for (int page = 1; ; page++) {
            HttpResponse<String> response = client.get(configuration.resolve(
                    "/crawls/" + crawlId + "/warcs?format=ids&page=" + page));
            requireOk("listing Bamboo WARCs", response);
            List<Long> batch = response.body().lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .map(Long::parseLong)
                    .toList();
            ids.addAll(batch);
            if (batch.isEmpty() || ids.size() >= response.headers()
                    .firstValue("Total").map(Long::parseLong)
                    .orElse((long) ids.size())) {
                break;
            }
        }
        return ids;
    }

    private BambooWarcSource warc(long id)
            throws IOException, InterruptedException {
        HttpResponse<String> response = client.get(
                configuration.resolve("/api/warcs/" + id));
        requireOk("reading Bamboo WARC metadata", response);
        JsonNode warc = SocialJson.mapper.readTree(response.body());
        return new BambooWarcSource(
                client, warc.path("id").asLong(id),
                warc.path("crawlId").asLong(),
                warc.path("filename").asText(),
                warc.path("size").asLong(),
                warc.path("sha256").asText(null));
    }

    private static void requireOk(
            String operation, HttpResponse<String> response)
            throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException(operation + " failed with HTTP " +
                    response.statusCode());
        }
    }
}
