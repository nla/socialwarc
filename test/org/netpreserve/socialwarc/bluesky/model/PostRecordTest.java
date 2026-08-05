package org.netpreserve.socialwarc.bluesky.model;

import org.junit.jupiter.api.Test;
import org.netpreserve.socialwarc.common.SocialJson;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostRecordTest {
    @Test
    void rendersTruncatedDisplayLinkUsingFacetDestination() {
        String destination =
                "https://www.example.com/reel/AbC123456?token=archive";
        PostRecord record = new PostRecord(
                "app.bsky.feed.post",
                "See this example:\nwww.example.com/reel/AbC123...",
                null,
                List.of(new PostRecord.Facet(
                        new PostRecord.Index(18, 48),
                        List.of(new PostRecord.Feature(
                                "app.bsky.richtext.facet#link",
                                null,
                                destination,
                                null)))),
                null);

        assertEquals(
                "See this example:<br>" +
                        "<a href=\"" + destination +
                        "\">www.example.com/reel/AbC123...</a>",
                record.richTextHtml());
    }

    @Test
    void rendersLinksUsingUtf8ByteOffsets() throws Exception {
        PostRecord record = record("""
                {
                  "text": "😀 Visit this & that",
                  "facets": [{
                    "index": {"byteStart": 11, "byteEnd": 15},
                    "features": [{
                      "$type": "app.bsky.richtext.facet#link",
                      "uri": "https://example.com/?a=1&b=2"
                    }]
                  }]
                }
                """);

        assertEquals(
                "😀 Visit <a href=\"https://example.com/?a=1&amp;b=2\">this</a> &amp; that",
                record.richTextHtml());
    }

    @Test
    void sortsFacetsAndRendersMentionsAndTags() throws Exception {
        PostRecord record = record("""
                {
                  "text": "@alice.test likes #archives",
                  "facets": [
                    {
                      "index": {"byteStart": 18, "byteEnd": 27},
                      "features": [{
                        "$type": "app.bsky.richtext.facet#tag",
                        "tag": "archives"
                      }]
                    },
                    {
                      "index": {"byteStart": 0, "byteEnd": 11},
                      "features": [{
                        "$type": "app.bsky.richtext.facet#mention",
                        "did": "did:plc:alice"
                      }]
                    }
                  ]
                }
                """);

        assertEquals(
                "<a href=\"https://bsky.app/profile/did:plc:alice\">@alice.test</a> likes " +
                        "<a href=\"https://bsky.app/hashtag/archives\">#archives</a>",
                record.richTextHtml());
    }

    @Test
    void ignoresInvalidAndOverlappingFacets() throws Exception {
        PostRecord record = record("""
                {
                  "text": "😀 one two",
                  "facets": [
                    {
                      "index": {"byteStart": 1, "byteEnd": 4},
                      "features": [{
                        "$type": "app.bsky.richtext.facet#link",
                        "uri": "https://invalid-offset.example"
                      }]
                    },
                    {
                      "index": {"byteStart": 5, "byteEnd": 8},
                      "features": [{
                        "$type": "app.bsky.richtext.facet#link",
                        "uri": "https://one.example"
                      }]
                    },
                    {
                      "index": {"byteStart": 6, "byteEnd": 12},
                      "features": [{
                        "$type": "app.bsky.richtext.facet#link",
                        "uri": "https://overlap.example"
                      }]
                    },
                    {
                      "index": {"byteStart": 9, "byteEnd": 12},
                      "features": [{
                        "$type": "app.bsky.richtext.facet#link",
                        "uri": "javascript:alert(1)"
                      }]
                    }
                  ]
                }
                """);

        assertEquals(
                "😀 <a href=\"https://one.example\">one</a> two",
                record.richTextHtml());
    }

    @Test
    void preservesLineBreaksAndEscapesPostsWithoutFacets() throws Exception {
        PostRecord record = record("""
                {"text": "first <line>\\nsecond & line"}
                """);

        assertEquals(
                "first &lt;line&gt;<br>second &amp; line",
                record.richTextHtml());
    }

    private static PostRecord record(String json) throws Exception {
        return SocialJson.mapper.readValue(json, PostRecord.class);
    }
}
