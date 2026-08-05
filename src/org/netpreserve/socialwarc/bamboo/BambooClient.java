package org.netpreserve.socialwarc.bamboo;

import tools.jackson.databind.JsonNode;
import org.netpreserve.socialwarc.common.SocialJson;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

final class BambooClient {
    private static final Pattern CRAWL_LOCATION =
            Pattern.compile("(?:.*/)?crawls/(\\d+)/?");

    record RemoteWarc(long id, long crawlId, String sha256) {
    }

    private final BambooConfiguration configuration;
    private final HttpClient client;
    private final OidcServiceAccount serviceAccount;

    BambooClient(BambooConfiguration configuration) {
        this.configuration = configuration;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        serviceAccount = configuration.oidc() == null
                ? null
                : new OidcServiceAccount(configuration.oidc());
    }

    long findOrCreateCrawl(BambooOutboxItem item)
            throws IOException, InterruptedException {
        Long existing = findCrawl(item.id);
        if (existing != null) {
            return existing;
        }
        Multipart body = Multipart.builder()
                .text("name", item.name)
                .text("description", item.description)
                .text("webrecorderCollectionId", item.id)
                .build();
        HttpResponse<String> response = send(auth -> HttpRequest.newBuilder(
                        configuration.resolve(
                                "/series/" + item.seriesId + "/upload"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", body.contentType())
                .headers(auth)
                .POST(body.publisher())
                .build());
        if (isRedirect(response.statusCode())) {
            return crawlId(response);
        }
        throw status("creating Bamboo crawl", response);
    }

    RemoteWarc ensureWarc(long crawlId, Path path, String expectedSha256)
            throws IOException, InterruptedException {
        RemoteWarc existing = findWarc(path.getFileName().toString());
        if (existing != null) {
            verify(existing, crawlId, expectedSha256, path);
            return existing;
        }

        Multipart body = Multipart.builder()
                .file("warcFile", path, "application/warc")
                .build();
        HttpResponse<String> response = send(auth -> HttpRequest.newBuilder(
                        configuration.resolve(
                                "/crawls/" + crawlId + "/warcs/upload"))
                .timeout(Duration.ofHours(12))
                .header("Content-Type", body.contentType())
                .headers(auth)
                .POST(body.publisher())
                .build());
        if (!isRedirect(response.statusCode())) {
            throw status("uploading " + path.getFileName(), response);
        }
        existing = findWarc(path.getFileName().toString());
        if (existing == null) {
            throw new IOException(
                    "Bamboo accepted the upload but did not catalogue " +
                            path.getFileName());
        }
        verify(existing, crawlId, expectedSha256, path);
        return existing;
    }

    private Long findCrawl(String externalId)
            throws IOException, InterruptedException {
        String id = URLEncoder.encode(externalId, StandardCharsets.UTF_8);
        HttpResponse<String> response = send(auth -> HttpRequest.newBuilder(
                        configuration.resolve(
                                "/crawls/by-webrecorder-id/" + id))
                .timeout(Duration.ofSeconds(30))
                .headers(auth)
                .GET()
                .build());
        if (response.statusCode() == 404) {
            return null;
        }
        if (isRedirect(response.statusCode())) {
            return crawlId(response);
        }
        throw status("looking up Bamboo crawl", response);
    }

    private RemoteWarc findWarc(String filename)
            throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        HttpResponse<String> response = send(auth -> HttpRequest.newBuilder(
                        configuration.resolve(
                                "/api/warcs/search/findByFilename?filename=" +
                                        encoded))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .headers(auth)
                .GET()
                .build());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw status("looking up Bamboo WARC", response);
        }
        JsonNode root = SocialJson.mapper.readTree(response.body());
        if (root == null || root.isNull()) {
            return null;
        }
        JsonNode id = root.get("id");
        if (id == null) {
            JsonNode embedded = root.path("_embedded").path("warcs");
            if (!embedded.isArray() || embedded.isEmpty()) {
                return null;
            }
            root = embedded.get(0);
            id = root.get("id");
        }
        if (id == null) {
            return null;
        }
        return new RemoteWarc(
                id.asLong(), root.path("crawlId").asLong(),
                root.path("sha256").asText(null));
    }

    private HttpResponse<String> send(
            Function<String[], HttpRequest> requestFactory)
            throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                requestFactory.apply(authorizationHeaders()),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && serviceAccount != null) {
            serviceAccount.invalidate();
            response = client.send(
                    requestFactory.apply(authorizationHeaders()),
                    HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    String[] authorizationHeaders() throws IOException {
        return serviceAccount == null
                ? new String[0]
                : new String[]{"Authorization", serviceAccount.bearer()};
    }

    HttpClient httpClient() {
        return client;
    }

    BambooConfiguration configuration() {
        return configuration;
    }

    HttpResponse<String> get(URI uri)
            throws IOException, InterruptedException {
        return send(auth -> HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/json")
                .headers(auth)
                .GET()
                .build());
    }

    private static boolean isRedirect(int status) {
        return status == 302 || status == 303;
    }

    private static long crawlId(HttpResponse<?> response) throws IOException {
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IOException(
                        "Bamboo response omitted the crawl Location"));
        var matcher = CRAWL_LOCATION.matcher(location);
        if (!matcher.matches()) {
            throw new IOException(
                    "Unexpected Bamboo crawl Location: " + location);
        }
        return Long.parseLong(matcher.group(1));
    }

    private static void verify(
            RemoteWarc warc, long crawlId, String expectedSha256, Path path)
            throws IOException {
        if (warc.crawlId() != crawlId) {
            throw new IOException("Bamboo already contains " +
                    path.getFileName() + " in crawl " + warc.crawlId());
        }
        if (warc.sha256() == null ||
                !warc.sha256().equalsIgnoreCase(expectedSha256)) {
            throw new IOException("Bamboo checksum does not match for " +
                    path.getFileName());
        }
    }

    private static BambooHttpException status(
            String operation, HttpResponse<String> response) {
        String body = response.body() == null
                ? ""
                : response.body().replaceAll("\\s+", " ").strip();
        if (body.length() > 500) {
            body = body.substring(0, 500);
        }
        return new BambooHttpException(
                response.statusCode(), operation + " failed with HTTP " +
                        response.statusCode() +
                        (body.isEmpty() ? "" : ": " + body));
    }

    static final class BambooHttpException extends IOException {
        private final int status;

        BambooHttpException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private record Multipart(String boundary, HttpRequest.BodyPublisher publisher) {
        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private final String boundary =
                    "socialwarc-" + UUID.randomUUID();
            private final List<HttpRequest.BodyPublisher> publishers =
                    new ArrayList<>();

            Builder text(String name, String value) {
                publishers.add(HttpRequest.BodyPublishers.ofByteArray((
                        "--" + boundary + "\r\n" +
                                "Content-Disposition: form-data; name=\"" +
                                name + "\"\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n\r\n" +
                                value + "\r\n")
                        .getBytes(StandardCharsets.UTF_8)));
                return this;
            }

            Builder file(String name, Path file, String contentType)
                    throws IOException {
                publishers.add(HttpRequest.BodyPublishers.ofByteArray((
                        "--" + boundary + "\r\n" +
                                "Content-Disposition: form-data; name=\"" +
                                name + "\"; filename=\"" +
                                safeFilename(file.getFileName().toString()) +
                                "\"\r\nContent-Type: " + contentType +
                                "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8)));
                publishers.add(HttpRequest.BodyPublishers.ofFile(file));
                publishers.add(HttpRequest.BodyPublishers.ofByteArray(
                        "\r\n".getBytes(StandardCharsets.US_ASCII)));
                return this;
            }

            Multipart build() {
                publishers.add(HttpRequest.BodyPublishers.ofByteArray((
                        "--" + boundary + "--\r\n")
                        .getBytes(StandardCharsets.US_ASCII)));
                return new Multipart(boundary,
                        HttpRequest.BodyPublishers.concat(
                                publishers.toArray(
                                        HttpRequest.BodyPublisher[]::new)));
            }

            private static String safeFilename(String filename) {
                return filename.replace("\\", "_").replace("\"", "_")
                        .replace("\r", "_").replace("\n", "_");
            }
        }
    }
}
