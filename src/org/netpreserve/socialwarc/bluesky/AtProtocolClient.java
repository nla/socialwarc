package org.netpreserve.socialwarc.bluesky;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeExecutor;
import dev.failsafe.FailsafeException;
import dev.failsafe.RetryPolicy;
import org.netpreserve.jwarc.HttpRequest;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.socialwarc.bluesky.model.FeedPage;
import org.netpreserve.socialwarc.bluesky.model.Profile;
import org.netpreserve.socialwarc.common.SocialJson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.time.temporal.ChronoUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.netpreserve.jwarc.MessageVersion.HTTP_1_0;

/**
 * Minimal unauthenticated XRPC client that records every exchange to WARC.
 */
public class AtProtocolClient implements BlueskyApi {
    public static final String PUBLIC_APPVIEW = "https://public.api.bsky.app";

    private static final FailsafeExecutor<HttpResponse> FAILSAFE = Failsafe.with(
            RetryPolicy.<HttpResponse>builder()
                    .handleResultIf(response -> response.status() == 429 || response.status() >= 500)
                    .handle(IOException.class)
                    .withBackoff(1, 30, ChronoUnit.SECONDS)
                    .withMaxRetries(4)
                    .build());

    private final String service;
    private final String userAgent;
    private final WarcWriter warcWriter;

    public AtProtocolClient(String service, String userAgent, WarcWriter warcWriter) {
        if (!service.startsWith("https://") && !service.startsWith("http://")) {
            service = "https://" + service;
        }
        this.service = service.replaceFirst("/+$", "");
        this.userAgent = userAgent;
        this.warcWriter = warcWriter;
    }

    @Override
    public Profile getProfile(String actor) throws IOException {
        return get("/xrpc/app.bsky.actor.getProfile?actor=" + encode(actor), Profile.class);
    }

    @Override
    public FeedPage getAuthorFeed(String actor, String cursor, int limit) throws IOException {
        String path = "/xrpc/app.bsky.feed.getAuthorFeed?actor=" + encode(actor) +
                "&filter=posts_with_replies&limit=" + limit;
        if (cursor != null) {
            path += "&cursor=" + encode(cursor);
        }
        return get(path, FeedPage.class);
    }

    private <T> T get(String path, Class<T> responseType) throws IOException {
        HttpResponse response;
        try {
            response = FAILSAFE.get(() -> send(path));
        } catch (FailsafeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("AT Protocol request failed", e.getCause());
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Unexpected response " + response.status() + " " +
                    response.reason() + " from " + service + path);
        }
        return SocialJson.mapper.readValue(response.bodyDecoded().stream(), responseType);
    }

    private HttpResponse send(String path) throws IOException {
        URI uri = URI.create(service + path);
        String requestTarget = uri.getRawQuery() == null
                ? uri.getRawPath()
                : uri.getRawPath() + "?" + uri.getRawQuery();
        var requestBuilder = new HttpRequest.Builder("GET", requestTarget)
                .version(HTTP_1_0)
                .addHeader("Accept", "application/json")
                .addHeader("Connection", "close")
                .addHeader("Host", uri.getRawAuthority());
        if (userAgent != null) {
            requestBuilder.addHeader("User-Agent", userAgent);
        }
        var request = requestBuilder.build();
        var buffer = new ByteArrayOutputStream();
        warcWriter.fetch(uri, request, buffer);
        return HttpResponse.parse(Channels.newChannel(
                new ByteArrayInputStream(buffer.toByteArray())));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }
}
