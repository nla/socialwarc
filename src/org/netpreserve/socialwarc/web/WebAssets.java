package org.netpreserve.socialwarc.web;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Content-addressed interface assets shared by renderers and the server. */
final class WebAssets {
    private static final Map<String, Asset> ASSETS = Map.of(
            "app.css", load("app.css"),
            "history.js", load("history.js"),
            "timeline.js", load("timeline.js"));

    private WebAssets() {
    }

    static Asset get(String name) {
        Asset asset = ASSETS.get(name);
        if (asset == null) {
            throw new IllegalArgumentException("Unknown interface asset: " + name);
        }
        return asset;
    }

    static String path(String name) {
        return get(name).path();
    }

    private static Asset load(String name) {
        String resource = "/org/netpreserve/socialwarc/web/" + name;
        try (InputStream input = WebAssets.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing interface asset: " + resource);
            }
            byte[] bytes = input.readAllBytes();
            String hash = hash(bytes);
            return new Asset(
                    bytes, "W/\"" + hash + "\"",
                    "/" + name + "?v=" + hash.substring(0, 16));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to load interface asset: " + resource, e);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    record Asset(byte[] bytes, String etag, String path) {
    }
}
