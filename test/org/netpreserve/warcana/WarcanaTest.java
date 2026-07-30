package org.netpreserve.warcana;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.mastodon.MastodonAccountReference;
import org.netpreserve.warcana.mastodon.model.Status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
    void refusesToOverwriteAnExistingWarcWithoutForce() throws Exception {
        var output = Files.createTempFile("warcana-test-", ".warc");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int result = Warcana.run(new String[]{
                        "archive-mastodon-account",
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
                        "extract-mastodon-json",
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
                  "media_attachments": [],
                  "mentions": [],
                  "tags": [],
                  "emojis": [],
                  "reblogs_count": 2,
                  "favourites_count": 3,
                  "replies_count": 1
                }
                """;
    }
}
