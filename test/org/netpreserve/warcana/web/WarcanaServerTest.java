package org.netpreserve.warcana.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.jsoup.Jsoup;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.collection.CollectionStore;
import org.netpreserve.warcana.common.model.Account;
import org.netpreserve.warcana.common.model.Attachment;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.resource.WarcResourceIndex;
import org.netpreserve.warcana.common.search.SearchHit;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarcanaServerTest {
    @Test
    void searchesPostsAndServesOnlyIndexedArchivedMedia() throws Exception {
        Path root = Files.createTempDirectory("warcana-server-");
        byte[] image = "recorded-image".getBytes(StandardCharsets.UTF_8);
        try {
            var store = new CollectionStore(root);
            store.initialize();
            writePostWarc(store.warcsDirectory().resolve(
                    "warcana-posts-test.warc"));
            writeMediaWarc(
                    store.warcsDirectory().resolve(
                            "warcana-media-test.warc"),
                    image);

            WarcanaServer created;
            try {
                created = new WarcanaServer(
                        root,
                        new InetSocketAddress("127.0.0.1", 0),
                        false,
                        ProgressReporter.NONE);
            } catch (SocketException e) {
                Assumptions.assumeTrue(
                        false,
                        "Loopback sockets are unavailable in this sandbox");
                return;
            }
            try (var server = created) {
                server.start();
                URI base = server.baseUri();
                try (var client = HttpClient.newHttpClient()) {
                    var page = client.send(
                            HttpRequest.newBuilder(base).build(),
                            BodyHandlers.ofString());
                    assertEquals(200, page.statusCode());
                    assertTrue(page.body().contains(
                            "<form class=search method=get action=/"));
                    assertTrue(page.headers()
                            .firstValue("Content-Security-Policy")
                            .orElseThrow()
                            .contains("default-src 'self'"));
                    var pageDocument = Jsoup.parse(page.body());
                    assertEquals(2, pageDocument.select(
                            ".timeline .timeline-bar").size());
                    assertEquals(
                            "2026-07-29",
                            pageDocument.selectFirst(".timeline")
                                    .attr("data-start"));
                    assertTrue(page.body().contains(
                            "<script src=/timeline.js defer></script>"));

                    var search = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve("/?q=Hello"))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, search.statusCode());
                    assertTrue(search.body().contains("<article class=post>"));
                    var searchDocument = Jsoup.parse(search.body());
                    assertEquals(
                            "Hello",
                            searchDocument.selectFirst(".post .content mark")
                                    .text());
                    assertEquals(
                            "Hello from the archive",
                            searchDocument.selectFirst(".post .content").text());
                    assertTrue(searchDocument.select(".snippet").isEmpty());
                    assertEquals(
                            "1 post",
                            searchDocument.selectFirst(".timeline-bar title")
                                    .text().replaceFirst("^.*: ", ""));
                    assertTrue(search.body().contains(
                            "/?platform=mastodon&amp;author=" +
                                    "alice%40example.social"));
                    var timelineScript = client.send(
                            HttpRequest.newBuilder(base.resolve("/timeline.js"))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, timelineScript.statusCode());
                    assertTrue(timelineScript.body().contains(
                            "setPointerCapture"));
                    assertTrue(search.body().contains(
                            ">@alice@example.social</a>"));
                    assertTrue(!search.body().contains("Open post"));
                    var replyLink = Jsoup.parse(search.body()).selectFirst(
                            ".reply-context a:containsOwn(Parent post)");
                    assertTrue(replyLink != null);
                    assertTrue(replyLink.attr("href").contains(
                            "identity=https%3A%2F%2Fexample.social%2Fusers%2F" +
                                    "alice%2Fstatuses%2F99"));
                    var dateLink = Jsoup.parse(search.body())
                            .selectFirst(".post-header .date-link");
                    assertTrue(dateLink != null);
                    var detail = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve(dateLink.attr("href")))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, detail.statusCode());
                    assertTrue(detail.body().contains("id=back-link"));
                    assertTrue(detail.body().contains(
                            "<script src=/history.js defer></script>"));
                    var detailDocument = Jsoup.parse(detail.body());
                    assertEquals(
                            2,
                            detailDocument.select(
                                    ".thread > article.post").size());
                    assertEquals(
                            1,
                            detailDocument.select(
                                    ".thread > article#current-post.current-post")
                                    .size());
                    var historyScript = client.send(
                            HttpRequest.newBuilder(base.resolve("/history.js"))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, historyScript.statusCode());
                    assertTrue(historyScript.body().contains(
                            "currentPost.scrollIntoView"));
                    assertTrue(detail.body().indexOf("Older post") <
                            detail.body().indexOf("Hello from the archive"));
                    assertTrue(detailDocument.selectFirst(".edited") != null);
                    assertTrue(detailDocument.selectFirst(".edited").text()
                            .contains("edited"));
                    assertEquals(
                            "warcana-posts-test.warc",
                            detailDocument.select(".provenance dt")
                                    .stream()
                                    .filter(element -> "WARC file".equals(
                                            element.text()))
                                    .findFirst()
                                    .orElseThrow()
                                    .nextElementSibling()
                                    .text());
                    assertTrue(detailDocument.select(".provenance dt")
                            .stream()
                            .anyMatch(element -> "WARC-Date".equals(
                                    element.text())));
                    var parentDetail = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve(replyLink.attr("href")))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, parentDetail.statusCode());
                    assertEquals(
                            2,
                            Jsoup.parse(parentDetail.body()).select(
                                    ".thread > article.post").size());
                    var commonLink = detailDocument.selectFirst(
                            "a:containsOwn(Common JSON)");
                    var originalLink = detailDocument.selectFirst(
                            "a:containsOwn(Original JSON)");
                    assertTrue(commonLink != null);
                    assertTrue(originalLink != null);
                    var commonJson = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve(commonLink.attr("href")))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, commonJson.statusCode());
                    assertEquals("application/json; charset=utf-8",
                            commonJson.headers().firstValue("Content-Type")
                                    .orElseThrow());
                    assertEquals("alice@example.social",
                            SocialJson.mapper.readTree(commonJson.body())
                                    .get("author").get("username").asText());
                    var originalJson = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve(originalLink.attr("href")))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, originalJson.statusCode());
                    var originalNode = SocialJson.mapper.readTree(
                            originalJson.body());
                    assertEquals("100", originalNode.get("id").asText());
                    assertEquals("alice",
                            originalNode.get("account").get("acct").asText());
                    var oldest = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve("/?sort=oldest"))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, oldest.statusCode());
                    assertTrue(oldest.body().indexOf("Older post") <
                            oldest.body().indexOf("Hello from the archive"));
                    var newest = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve("/?sort=newest"))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, newest.statusCode());
                    assertTrue(newest.body().indexOf("Hello from the archive") <
                            newest.body().indexOf("Older post"));
                    var withMedia = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve("/?hasMedia=true"))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, withMedia.statusCode());
                    assertTrue(withMedia.body().contains(
                            "Hello from the archive"));
                    assertTrue(!withMedia.body().contains("Older post"));
                    String mediaUrl = "https://media.example/image.jpg";
                    String id = WarcResourceIndex.idForUrl(mediaUrl);
                    assertEquals(WarcResourceIndex.idForUrl(mediaUrl), id);
                    assertTrue(search.body().contains("src=/media/" + id));
                    var mediaImageLink = Jsoup.parse(search.body())
                            .selectFirst("a.media-image");
                    assertTrue(mediaImageLink != null);
                    assertEquals(
                            "/media/" + id,
                            mediaImageLink.attr("href"));
                    assertEquals(
                            "/media/" + id,
                            mediaImageLink.selectFirst("img").attr("src"));

                    var media = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve("/media/" + id))
                                    .build(),
                            BodyHandlers.ofByteArray());
                    assertEquals(200, media.statusCode());
                    assertEquals("image/jpeg",
                            media.headers().firstValue("Content-Type")
                                    .orElseThrow());
                    assertArrayEquals(image, media.body());

                    var range = client.send(
                            HttpRequest.newBuilder(
                                            base.resolve("/media/" + id))
                                    .header("Range", "bytes=0-7")
                                    .build(),
                            BodyHandlers.ofByteArray());
                    assertEquals(206, range.statusCode());
                    assertEquals("bytes 0-7/" + image.length,
                            range.headers().firstValue("Content-Range")
                                    .orElseThrow());
                    assertArrayEquals(
                            "recorded".getBytes(StandardCharsets.UTF_8),
                            range.body());

                    String playlistUrl =
                            "https://video.example/archive/master.m3u8";
                    String playlistId =
                            WarcResourceIndex.idForUrl(playlistUrl);
                    String segmentId = WarcResourceIndex.idForUrl(
                            "https://video.example/archive/segment.ts");
                    var playlist = client.send(
                            HttpRequest.newBuilder(
                                    base.resolve("/media/" + playlistId))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(200, playlist.statusCode());
                    assertTrue(playlist.body()
                            .contains("/media/" + segmentId));
                    assertTrue(!playlist.body().contains("video.example"));

                    var missing = client.send(
                            HttpRequest.newBuilder(base.resolve(
                                            "/media/" + "0".repeat(64)))
                                    .build(),
                            BodyHandlers.ofString());
                    assertEquals(404, missing.statusCode());
                }
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void rendersRepostsUsingTheOriginalAuthorsIdentity() {
        var originalAuthor = new Account(
                "alice@example.test", "Alice",
                "https://media.example/alice.jpg", null);
        var reposter = new Account(
                "bob@example.test", "Bob",
                "https://media.example/bob.jpg", null);
        var quotedAuthor = new Account(
                "carol@example.test", "Carol",
                "https://media.example/carol.jpg", null);
        var quoted = new Post(
                "https://example.test/carol/quoted",
                Instant.parse("2026-07-29T06:00:00Z"),
                quotedAuthor, null, null, List.of(),
                "<p>Quoted post</p>", List.of(), null, null,
                null, null, null, null, "quoted", null, null);
        var original = new Post(
                "https://example.test/alice/1",
                Instant.parse("2026-07-30T07:00:00Z"),
                originalAuthor, null, null, List.of(),
                "<p>Original post</p>", List.of(), null, quoted,
                1L, 2L, 0L, 3L, "original", null, null);
        var repost = new Post(
                "https://example.test/bob/repost/1",
                Instant.parse("2026-07-30T08:00:00Z"),
                reposter, null, null, List.of(),
                "", List.of(), original, null,
                null, null, null, null, "repost", null, null);
        var hit = new SearchHit(
                null, "", "mastodon", "repost-1",
                "https://api.example.test/statuses", "urn:uuid:test",
                "posts.warc", 123L,
                Instant.parse("2026-07-30T09:00:00Z"), repost);
        String aliceAvatar = "a".repeat(64);
        String bobAvatar = "b".repeat(64);
        String carolAvatar = "c".repeat(64);

        String html = CollectionHtmlRenderer.post(hit, List.of(hit), Map.of(
                originalAuthor.avatarUrl(), aliceAvatar,
                reposter.avatarUrl(), bobAvatar,
                quotedAuthor.avatarUrl(), carolAvatar), Map.of(), null);

        assertTrue(html.contains("Bob reposted"));
        assertTrue(html.contains(
                "<img class=mini-avatar src=/media/" + bobAvatar));
        assertTrue(html.indexOf("src=/media/" + bobAvatar) <
                html.indexOf("Bob reposted"));
        assertTrue(html.contains("<strong>Alice</strong>"));
        assertTrue(html.contains("src=/media/" + aliceAvatar));
        assertTrue(html.contains("Original post"));
        assertTrue(html.contains("Quoted post"));
        assertTrue(html.contains(
                "<blockquote class=quote><header><img class=mini-avatar " +
                        "src=/media/" + carolAvatar));
        var quotedHeader = Jsoup.parse(html).selectFirst(".quote > header");
        assertTrue(quotedHeader != null);
        assertEquals("@carol@example.test",
                quotedHeader.selectFirst("a.username").text());
        assertTrue(quotedHeader.selectFirst("a.date-link") != null);
        assertTrue(html.contains(
                "/post?platform=mastodon&amp;identity=repost-1&amp;quoted=" +
                        "https%3A%2F%2Fexample.test%2Fcarol%2Fquoted"));
        assertTrue(html.contains("2026-07-29T06:00:00Z"));
    }

    @Test
    void rendersLinkAttachmentsAsPreviewCards() {
        String thumbnailUrl = "https://media.example/preview.jpg";
        String thumbnailId = "d".repeat(64);
        var attachment = new Attachment(
                "https://example.test/story",
                "link",
                "A synthetic preview image",
                List.of(),
                "Example story",
                "A synthetic preview description",
                thumbnailUrl);
        var post = new Post(
                "https://social.example/post/1",
                Instant.parse("2026-07-30T07:00:00Z"),
                new Account("alice.example", "Alice", null, null),
                null, null, List.of(), "Example post",
                List.of(attachment), null, null,
                0L, 0L, 0L, 0L, "post-1", null, null);
        var hit = new SearchHit(
                null, "", "bluesky", "post-1",
                "https://api.example.test/feed", "urn:uuid:test",
                "posts.warc", 123L,
                Instant.parse("2026-07-30T09:00:00Z"), post);

        var document = Jsoup.parse(CollectionHtmlRenderer.post(
                hit, List.of(hit),
                Map.of(thumbnailUrl, thumbnailId), Map.of(), null));
        var card = document.selectFirst("a.link-card");

        assertTrue(card != null);
        assertEquals("https://example.test/story", card.attr("href"));
        assertEquals("Example story", card.selectFirst("strong").text());
        assertTrue(card.text().contains("A synthetic preview description"));
        assertEquals(
                "/media/" + thumbnailId,
                card.selectFirst("img").attr("src"));
        assertEquals(
                "A synthetic preview image",
                card.selectFirst("img").attr("alt"));
    }

    @Test
    void rendersMultipleImagesAsAGrid() {
        String firstUrl = "https://media.example/first.jpg";
        String secondUrl = "https://media.example/second.jpg";
        String firstId = "1".repeat(64);
        String secondId = "2".repeat(64);
        var attachments = List.of(
                new Attachment(
                        firstUrl, "image", "First image", List.of(),
                        null, null, null),
                new Attachment(
                        secondUrl, "image", "Second image", List.of(),
                        null, null, null));
        var post = new Post(
                "https://social.example/post/2",
                Instant.parse("2026-07-30T07:00:00Z"),
                new Account("alice.example", "Alice", null, null),
                null, null, List.of(), "Example post",
                attachments, null, null,
                0L, 0L, 0L, 0L, "post-2", null, null);
        var hit = new SearchHit(
                null, "", "bluesky", "post-2",
                "https://api.example.test/feed", "urn:uuid:test",
                "posts.warc", 123L,
                Instant.parse("2026-07-30T09:00:00Z"), post);

        var document = Jsoup.parse(CollectionHtmlRenderer.post(
                hit,
                List.of(hit),
                Map.of(firstUrl, firstId, secondUrl, secondId),
                Map.of(),
                null));
        var grid = document.selectFirst(".media.image-grid");

        assertTrue(grid != null);
        assertEquals(2, grid.select("a.media-image").size());
        assertEquals("/media/" + firstId,
                grid.select("a.media-image").getFirst().attr("href"));
        assertEquals("/media/" + secondId,
                grid.select("a.media-image").getLast().attr("href"));
    }

    @Test
    void selectsTheSpecificQuotedPostFromOriginalBlueskyJson()
            throws Exception {
        var root = SocialJson.mapper.readTree("""
                {
                  "feed": [{
                    "post": {
                      "uri": "at://did:plc:parent/app.bsky.feed.post/parent",
                      "author": {"handle": "parent.example"},
                      "embed": {
                        "record": {
                          "record": {
                            "uri": "at://did:plc:quoted/app.bsky.feed.post/quoted",
                            "author": {"handle": "quoted.example"},
                            "value": {"text": "Quoted original"}
                          }
                        }
                      }
                    }
                  }]
                }
                """);

        var selected = WarcanaServer.findOriginalJson(
                root,
                "bluesky",
                null,
                "https://bsky.app/profile/quoted.example/post/quoted");

        assertTrue(selected != null);
        assertEquals(
                "at://did:plc:quoted/app.bsky.feed.post/quoted",
                selected.get("uri").asText());
        assertEquals(
                "Quoted original",
                selected.get("value").get("text").asText());
    }

    private static void writePostWarc(Path path) throws Exception {
        String json = """
                [{
                  "id": "100",
                  "uri": "https://example.social/users/alice/statuses/100",
                  "url": "https://example.social/@alice/100",
                  "created_at": "2026-07-30T07:00:00Z",
                  "edited_at": "2026-07-30T08:00:00Z",
                  "in_reply_to_id": "99",
                  "in_reply_to_account_id": "42",
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
                  "content": "<p>Hello from the archive</p>",
                  "visibility": "public",
                  "sensitive": false,
                  "spoiler_text": "",
                  "media_attachments": [{
                    "id": "media-1",
                    "type": "image",
                    "url": "https://media.example/image.jpg",
                    "preview_url": "https://media.example/image.jpg",
                    "description": "An archived test image"
                  }],
                  "mentions": [],
                  "tags": [],
                  "emojis": [],
                  "reblogs_count": 2,
                  "favourites_count": 3,
                  "replies_count": 1
                }, {
                  "id": "99",
                  "uri": "https://example.social/users/alice/statuses/99",
                  "url": "https://example.social/@alice/99",
                  "created_at": "2026-07-29T07:00:00Z",
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
                  "content": "<p>Older post</p>",
                  "visibility": "public",
                  "sensitive": false,
                  "spoiler_text": "",
                  "media_attachments": [],
                  "mentions": [],
                  "tags": [],
                  "emojis": [],
                  "reblogs_count": 0,
                  "favourites_count": 0,
                  "replies_count": 0,
                  "card": {
                    "url": "https://example.test/older-story",
                    "title": "Older story",
                    "description": "Link-only attachment",
                    "type": "link",
                    "author_name": "Example author"
                  }
                }]
                """;
        try (var writer = new WarcWriter(path)) {
            var http = new HttpResponse.Builder(200, "OK")
                    .body(
                            MediaType.JSON,
                            json.getBytes(StandardCharsets.UTF_8))
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://example.social/api/v1/accounts/42/statuses?limit=40")
                    .body(http)
                    .build());
        }
    }

    private static void writeMediaWarc(Path path, byte[] image)
            throws Exception {
        try (var writer = new WarcWriter(path)) {
            var http = new HttpResponse.Builder(200, "OK")
                    .body(MediaType.parse("image/jpeg"), image)
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://media.example/image.jpg")
                    .body(http)
                    .build());
            var playlist = new HttpResponse.Builder(200, "OK")
                    .body(
                            MediaType.parse(
                                    "application/vnd.apple.mpegurl"),
                            "#EXTM3U\n#EXTINF:4,\nsegment.ts\n"
                                    .getBytes(StandardCharsets.UTF_8))
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://video.example/archive/master.m3u8")
                    .body(playlist)
                    .build());
            var segment = new HttpResponse.Builder(200, "OK")
                    .body(
                            MediaType.parse("video/mp2t"),
                            "segment".getBytes(StandardCharsets.UTF_8))
                    .build();
            writer.write(new WarcResponse.Builder(
                    "https://video.example/archive/segment.ts")
                    .body(segment)
                    .build());
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
