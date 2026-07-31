package org.netpreserve.warcana;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.bluesky.BlueskyAccountReference;
import org.netpreserve.warcana.bluesky.model.PostView;
import org.netpreserve.warcana.bluesky.model.Embed;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.mastodon.MastodonAccountReference;
import org.netpreserve.warcana.mastodon.model.Status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarcanaTest {
    @Test
    void parsesAccountNamesAndUrls() {
        assertEquals(
                new MastodonAccountReference("https://example.social", "alice"),
                MastodonAccountReference.parse("@alice@example.social"));
        assertEquals(
                new MastodonAccountReference("https://example.social", "alice"),
                MastodonAccountReference.parse("alice@example.social"));
        assertEquals(
                new MastodonAccountReference("https://example.social", "alice"),
                MastodonAccountReference.parse("https://example.social/@alice"));
    }

    @Test
    void parsesBlueskyHandlesAndProfileUrls() {
        assertEquals(
                new BlueskyAccountReference("alice.example"),
                BlueskyAccountReference.parse("@alice.example"));
        assertEquals(
                new BlueskyAccountReference("alice.example"),
                BlueskyAccountReference.parse(
                        "https://bsky.app/profile/alice.example"));
    }

    @Test
    void refusesToOverwriteAnExistingWarcWithoutForce() throws Exception {
        var output = Files.createTempFile("warcana-test-", ".warc");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int result = Warcana.run(new String[]{
                        "archive",
                        "--platform", "mastodon",
                        "--output", output.toString(),
                        "@alice@example.social"
                },
                new PrintStream(stdout),
                new PrintStream(stderr));

        assertEquals(2, result);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("use --force"));
        Files.delete(output);
    }

    @Test
    void genericArchiveRequiresPlatformForAmbiguousHandles() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int result = Warcana.run(new String[]{
                        "archive",
                        "@alice.example"
                },
                new PrintStream(stdout),
                new PrintStream(stderr));

        assertEquals(2, result);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("--platform"));
    }

    @Test
    void deserializesMastodonStatusWithJavaTimeValues() throws Exception {
        Status status = SocialJson.mapper.readValue("""
                {
                  "id": "100",
                  "uri": "https://example.social/users/alice/statuses/100",
                  "created_at": "2026-07-30T07:00:00Z",
                  "account": {
                    "id": "42",
                    "username": "alice",
                    "acct": "alice",
                    "url": "https://example.social/@alice",
                    "display_name": "Alice",
                    "note": "",
                    "avatar": "",
                    "avatar_static": "",
                    "header": "",
                    "header_static": ""
                  },
                  "content": "<p>Hello</p>",
                  "visibility": "public",
                  "sensitive": false,
                  "spoiler_text": "",
                  "media_attachments": [],
                  "mentions": [],
                  "tags": [],
                  "emojis": [],
                  "reblogs_count": 0,
                  "favourites_count": 0,
                  "replies_count": 0
                }
                """, Status.class);

        assertEquals("100", status.id());
        assertEquals("alice", status.account().username());
        assertEquals("2026-07-30T07:00:00Z", status.createdAt().toString());
    }

    @Test
    void discoversMastodonMediaOriginalsPreviewsAndProfileImages() throws Exception {
        Status status = SocialJson.mapper.readValue(mastodonStatusJson(), Status.class);

        assertEquals(
                java.util.List.of(
                        "https://example.social/avatar.png",
                        "https://media.example/attachment.mp4",
                        "https://media.example/attachment-preview.jpg"),
                status.mediaUrls());
        assertEquals(
                java.util.List.of(
                        "https://example.social/avatar.png",
                        "https://media.example/attachment-preview.jpg"),
                status.mediaUrls(false));
    }

    @Test
    void excludesBlueskyVideoButRetainsItsThumbnail() throws Exception {
        var embed = SocialJson.mapper.readValue("""
                {
                  "$type": "app.bsky.embed.video#view",
                  "playlist": "https://video.example/master.m3u8",
                  "thumbnail": "https://video.example/thumbnail.jpg"
                }
                """, Embed.class);
        var post = new PostView(
                "", "", null, null, embed,
                0, 0, 0, 0, null);

        assertEquals(
                java.util.List.of("https://video.example/thumbnail.jpg"),
                post.mediaUrls(false));
        assertEquals(
                java.util.List.of(
                        "https://video.example/master.m3u8",
                        "https://video.example/thumbnail.jpg"),
                post.mediaUrls());
    }

    @Test
    void rejectsConflictingVideoArchiveOptions() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int result = Warcana.run(new String[]{
                        "archive",
                        "--exclude-video",
                        "--all-video-variants",
                        "@alice.example"
                },
                new PrintStream(stdout),
                new PrintStream(stderr));

        assertEquals(2, result);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("cannot be used together"));
    }

    @Test
    void parsesMediaByteLimits() {
        assertEquals(42, Warcana.parseByteSize("42"));
        assertEquals(10L * 1024 * 1024, Warcana.parseByteSize("10M"));
        assertEquals(2L * 1024 * 1024 * 1024, Warcana.parseByteSize("2 GiB"));
    }

    @Test
    void extractsMastodonStatusesFromWarcAsCommonModelJson() throws Exception {
        var warc = Files.createTempFile("warcana-input-", ".warc");
        var jsonOutput = Files.createTempFile("warcana-output-", ".json");
        Files.delete(jsonOutput);
        byte[] responseBody = ("[" + mastodonStatusJson() + "]")
                .getBytes(StandardCharsets.UTF_8);

        try (var writer = new WarcWriter(warc)) {
            var http = new HttpResponse.Builder(200, "OK")
                    .body(MediaType.JSON, responseBody)
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://example.social/api/v1/accounts/42/statuses?limit=40")
                    .body(http)
                    .build());
        }

        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int result = Warcana.run(new String[]{
                        "extract",
                        "--pretty",
                        "--output", jsonOutput.toString(),
                        warc.toString()
                },
                new PrintStream(stdout),
                new PrintStream(stderr));

        assertEquals(0, result, stderr.toString(StandardCharsets.UTF_8));
        var json = SocialJson.mapper.readTree(jsonOutput);
        assertEquals(1, json.get("totalHits").asInt());
        assertEquals("https://example.social/@alice/100",
                json.get("posts").get(0).get("url").asText());
        assertEquals("alice", json.get("posts").get(0).get("author").get("username").asText());
        assertEquals("example.social", json.get("posts").get(0).get("site").get("name").asText());
        assertEquals("<p>Hello</p>", json.get("posts").get(0).get("content").asText());

        Files.delete(warc);
        Files.delete(jsonOutput);
    }

    @Test
    void extractsBlueskyFeedFromWarcAsCommonModelJson() throws Exception {
        var warc = Files.createTempFile("warcana-bluesky-input-", ".warc");
        var jsonOutput = Files.createTempFile("warcana-bluesky-output-", ".json");
        Files.delete(jsonOutput);

        try (var writer = new WarcWriter(warc)) {
            var http = new HttpResponse.Builder(200, "OK")
                    .body(MediaType.JSON, blueskyFeedJson().getBytes(StandardCharsets.UTF_8))
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" +
                            "?actor=did%3Aplc%3Aexample&limit=100")
                    .body(http)
                    .build());
        }

        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int result = Warcana.run(new String[]{
                        "extract",
                        "--pretty",
                        "--output", jsonOutput.toString(),
                        warc.toString()
                },
                new PrintStream(stdout),
                new PrintStream(stderr));

        assertEquals(0, result, stderr.toString(StandardCharsets.UTF_8));
        var json = SocialJson.mapper.readTree(jsonOutput);
        var post = json.get("posts").get(0);
        assertEquals(1, json.get("totalHits").asInt());
        assertEquals("https://bsky.app/profile/alice.example/post/example-post",
                post.get("url").asText());
        assertEquals("alice.example", post.get("author").get("username").asText());
        assertEquals("Bluesky", post.get("site").get("name").asText());
        assertEquals("Example post", post.get("content").asText());
        assertEquals("image", post.get("attachments").get(0).get("type").asText());

        Files.delete(warc);
        Files.delete(jsonOutput);
    }

    @Test
    void genericExtractReadsMixedPlatformWarcInOnePass() throws Exception {
        var warc = Files.createTempFile("warcana-mixed-input-", ".warc");
        var jsonOutput = Files.createTempFile("warcana-mixed-output-", ".json");
        Files.delete(jsonOutput);

        try (var writer = new WarcWriter(warc)) {
            var mastodonHttp = new HttpResponse.Builder(200, "OK")
                    .body(MediaType.JSON,
                            ("[" + mastodonStatusJson() + "]").getBytes(StandardCharsets.UTF_8))
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://example.social/api/v1/accounts/42/statuses?limit=40")
                    .body(mastodonHttp)
                    .build());

            var blueskyHttp = new HttpResponse.Builder(200, "OK")
                    .body(MediaType.JSON, blueskyFeedJson().getBytes(StandardCharsets.UTF_8))
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" +
                            "?actor=did%3Aplc%3Aexample&limit=100")
                    .body(blueskyHttp)
                    .build());
        }

        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int result = Warcana.run(new String[]{
                        "extract",
                        "--output", jsonOutput.toString(),
                        warc.toString()
                },
                new PrintStream(stdout),
                new PrintStream(stderr));

        assertEquals(0, result, stderr.toString(StandardCharsets.UTF_8));
        var json = SocialJson.mapper.readTree(jsonOutput);
        assertEquals(2, json.get("totalHits").asInt());
        assertEquals("example.social", json.get("posts").get(0).get("site").get("name").asText());
        assertEquals("Bluesky", json.get("posts").get(1).get("site").get("name").asText());

        Files.delete(warc);
        Files.delete(jsonOutput);
    }

    @Test
    void buildsReusesAndFiltersFullTextSearchIndex() throws Exception {
        var temporary = Files.createTempDirectory("warcana-search-");
        var warc = temporary.resolve("mixed.warc");
        var index = temporary.resolve("mixed.index");
        try {
            try (var writer = new WarcWriter(warc)) {
                var mastodonHttp = new HttpResponse.Builder(200, "OK")
                        .body(MediaType.JSON,
                                ("[" + mastodonStatusJson() + "]")
                                        .getBytes(StandardCharsets.UTF_8))
                        .build();
                writer.write(new WarcResponse.Builder(
                        "https://example.social/api/v1/accounts/42/statuses?limit=40")
                        .body(mastodonHttp)
                        .build());

                var blueskyHttp = new HttpResponse.Builder(200, "OK")
                        .body(MediaType.JSON,
                                blueskyFeedJson()
                                        .replace("2026-07-30", "2026-07-31")
                                        .getBytes(StandardCharsets.UTF_8))
                        .build();
                writer.write(new WarcResponse.Builder(
                        "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" +
                                "?actor=did%3Aplc%3Aexample&limit=100")
                        .body(blueskyHttp)
                        .build());
            }

            var stdout = new ByteArrayOutputStream();
            var stderr = new ByteArrayOutputStream();
            int firstResult = Warcana.run(new String[]{
                            "search",
                            "--index", index.toString(),
                            "--author", "@alice@example.social",
                            "--format", "json",
                            warc.toString(),
                            "Hello"
                    },
                    new PrintStream(stdout),
                    new PrintStream(stderr));

            assertEquals(0, firstResult, stderr.toString(StandardCharsets.UTF_8));
            var firstJson = SocialJson.mapper.readTree(stdout.toByteArray());
            assertEquals(1, firstJson.get("totalHits").asInt());
            assertEquals("mastodon",
                    firstJson.get("hits").get(0).get("platform").asText());
            assertTrue(firstJson.get("hits").get(0).get("snippet").asText()
                    .contains("<b>Hello</b>"));
            assertTrue(stderr.toString(StandardCharsets.UTF_8)
                    .contains("Building search index"));

            stdout.reset();
            stderr.reset();
            int secondResult = Warcana.run(new String[]{
                            "search",
                            "--index", index.toString(),
                            "--platform", "bluesky",
                            "--author", "alice.example",
                            "--since", "2026-07-30",
                            "--until", "2026-07-31",
                            "--has-media",
                            "--format", "json",
                            warc.toString(),
                            "Example"
                    },
                    new PrintStream(stdout),
                    new PrintStream(stderr));

            assertEquals(0, secondResult, stderr.toString(StandardCharsets.UTF_8));
            var secondJson = SocialJson.mapper.readTree(stdout.toByteArray());
            assertEquals(1, secondJson.get("totalHits").asInt());
            assertEquals("bluesky",
                    secondJson.get("hits").get(0).get("platform").asText());
            assertTrue(stderr.toString(StandardCharsets.UTF_8)
                    .contains("Using search index"));

            stdout.reset();
            stderr.reset();
            int recentResult = Warcana.run(new String[]{
                            "search",
                            "--index", index.toString(),
                            "--format", "json",
                            warc.toString()
                    },
                    new PrintStream(stdout),
                    new PrintStream(stderr));

            assertEquals(0, recentResult, stderr.toString(StandardCharsets.UTF_8));
            var recentJson = SocialJson.mapper.readTree(stdout.toByteArray());
            assertEquals(2, recentJson.get("totalHits").asInt());
            assertEquals("bluesky",
                    recentJson.get("hits").get(0).get("platform").asText());
            assertEquals("2026-07-31T07:00:00Z",
                    recentJson.get("hits").get(0).get("post")
                            .get("createdAt").asText());
            assertTrue(recentJson.get("hits").get(0).get("score").isNull());
            assertEquals("mastodon",
                    recentJson.get("hits").get(1).get("platform").asText());

            stdout.reset();
            stderr.reset();
            Files.setLastModifiedTime(
                    warc,
                    FileTime.fromMillis(
                            Files.getLastModifiedTime(warc).toMillis() + 5_000));
            int staleResult = Warcana.run(new String[]{
                            "search",
                            "--index", index.toString(),
                            warc.toString(),
                            "Example"
                    },
                    new PrintStream(stdout),
                    new PrintStream(stderr));

            assertEquals(0, staleResult, stderr.toString(StandardCharsets.UTF_8));
            assertTrue(stderr.toString(StandardCharsets.UTF_8)
                    .contains("Building search index"));
        } finally {
            if (Files.exists(temporary)) {
                try (var paths = Files.walk(temporary)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
    }

    @Test
    void textSearchOutputDecodesHtmlEntitiesWithoutLosingHighlights() throws Exception {
        var temporary = Files.createTempDirectory("warcana-search-entities-");
        var warc = temporary.resolve("mastodon.warc");
        var index = temporary.resolve("mastodon.index");
        try {
            String status = mastodonStatusJson()
                    .replace(
                            "<p>Hello</p>",
                            "<p>A Venn diagram with owners &amp; people who aren&#39;t owners.</p>");
            try (var writer = new WarcWriter(warc)) {
                var http = new HttpResponse.Builder(200, "OK")
                        .body(
                                MediaType.JSON,
                                ("[" + status + "]").getBytes(StandardCharsets.UTF_8))
                        .build();
                writer.write(new WarcResponse.Builder(
                        "https://example.social/api/v1/accounts/42/statuses?limit=40")
                        .body(http)
                        .build());
            }

            var stdout = new ByteArrayOutputStream();
            var stderr = new ByteArrayOutputStream();
            int result = Warcana.run(new String[]{
                            "search",
                            "--quiet",
                            "--index", index.toString(),
                            warc.toString(),
                            "Venn"
                    },
                    new PrintStream(stdout),
                    new PrintStream(stderr));

            assertEquals(0, result, stderr.toString(StandardCharsets.UTF_8));
            String text = stdout.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("[Venn] diagram"));
            assertTrue(text.contains("owners & people"));
            assertTrue(text.contains("who aren't owners"));
            assertTrue(text.lines()
                    .filter(line -> line.startsWith("@alice · mastodon"))
                    .findFirst()
                    .orElseThrow()
                    .endsWith("https://example.social/@alice/100"));
            assertTrue(!text.contains("&amp;"));
            assertTrue(!text.contains("&#"));

            stdout.reset();
            int colorResult = Warcana.run(new String[]{
                            "search",
                            "--quiet",
                            "--color", "always",
                            "--index", index.toString(),
                            warc.toString(),
                            "Venn"
                    },
                    new PrintStream(stdout),
                    new PrintStream(stderr));

            assertEquals(0, colorResult, stderr.toString(StandardCharsets.UTF_8));
            String colored = stdout.toString(StandardCharsets.UTF_8);
            assertTrue(colored.contains("\u001b[1m1 result\u001b[0m"));
            assertTrue(colored.contains("\u001b[1;33mVenn\u001b[0m"));
            assertTrue(colored.contains("owners & people"));
        } finally {
            if (Files.exists(temporary)) {
                try (var paths = Files.walk(temporary)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
    }

    private static String mastodonStatusJson() {
        return """
                {
                  "id": "100",
                  "uri": "https://example.social/users/alice/statuses/100",
                  "url": "https://example.social/@alice/100",
                  "created_at": "2026-07-30T07:00:00Z",
                  "account": {
                    "id": "42",
                    "username": "alice",
                    "acct": "alice",
                    "url": "https://example.social/@alice",
                    "display_name": "Alice",
                    "note": "",
                    "avatar": "https://example.social/avatar.png",
                    "avatar_static": "https://example.social/avatar.png",
                    "header": "",
                    "header_static": ""
                  },
                  "content": "<p>Hello</p>",
                  "visibility": "public",
                  "sensitive": false,
                  "spoiler_text": "",
                  "media_attachments": [{
                    "id": "media-1",
                    "type": "video",
                    "url": "https://media.example/attachment.mp4",
                    "preview_url": "https://media.example/attachment-preview.jpg"
                  }],
                  "mentions": [],
                  "tags": [],
                  "emojis": [],
                  "reblogs_count": 2,
                  "favourites_count": 3,
                  "replies_count": 1
                }
                """;
    }

    private static String blueskyFeedJson() {
        return """
                {
                  "feed": [{
                    "post": {
                      "uri": "at://did:plc:example/app.bsky.feed.post/example-post",
                      "cid": "example-cid",
                      "author": {
                        "did": "did:plc:example",
                        "handle": "alice.example",
                        "displayName": "Alice Example",
                        "avatar": "https://cdn.example/avatar.png"
                      },
                      "record": {
                        "$type": "app.bsky.feed.post",
                        "text": "Example post",
                        "createdAt": "2026-07-30T07:00:00Z"
                      },
                      "embed": {
                        "$type": "app.bsky.embed.images#view",
                        "images": [{
                          "thumb": "https://cdn.example/image-thumb.jpg",
                          "fullsize": "https://cdn.example/image.jpg",
                          "alt": "Example image",
                          "aspectRatio": {"width": 800, "height": 600}
                        }]
                      },
                      "replyCount": 1,
                      "repostCount": 2,
                      "likeCount": 3,
                      "quoteCount": 4,
                      "indexedAt": "2026-07-30T07:00:01Z"
                    }
                  }]
                }
                """;
    }
}
