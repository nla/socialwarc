package org.netpreserve.warcana.bluesky.model;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.model.Site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlueskyEmbedModelTest {
    @Test
    void deserializesNestedEmbedViewsUsingTypeAnnotations() throws Exception {
        PostView post = SocialJson.mapper.readValue("""
                {
                  "uri": "at://did:plc:alice/app.bsky.feed.post/outer",
                  "cid": "outer-cid",
                  "author": {
                    "did": "did:plc:alice",
                    "handle": "alice.example"
                  },
                  "record": {
                    "$type": "app.bsky.feed.post",
                    "text": "Outer post",
                    "createdAt": "2026-07-30T07:00:00Z"
                  },
                  "embed": {
                    "$type": "app.bsky.embed.recordWithMedia#view",
                    "media": {
                      "$type": "app.bsky.embed.images#view",
                      "images": [{
                        "thumb": "https://cdn.example/outer-thumb.jpg",
                        "fullsize": "https://cdn.example/outer.jpg",
                        "alt": "Outer image"
                      }]
                    },
                    "record": {
                      "$type": "app.bsky.embed.record#view",
                      "record": {
                        "$type": "app.bsky.embed.record#viewRecord",
                        "uri": "at://did:plc:bob/app.bsky.feed.post/quoted",
                        "cid": "quoted-cid",
                        "author": {
                          "did": "did:plc:bob",
                          "handle": "bob.example"
                        },
                        "value": {
                          "$type": "app.bsky.feed.post",
                          "text": "Quoted post",
                          "createdAt": "2026-07-29T07:00:00Z"
                        },
                        "embeds": [{
                          "$type": "app.bsky.embed.images#view",
                          "images": [{
                            "thumb": "https://cdn.example/quoted-thumb.jpg",
                            "fullsize": "https://cdn.example/quoted.jpg",
                            "alt": "Quoted image"
                          }]
                        }],
                        "replyCount": 1,
                        "repostCount": 2,
                        "likeCount": 3,
                        "quoteCount": 4,
                        "indexedAt": "2026-07-29T07:00:01Z"
                      }
                    }
                  },
                  "replyCount": 0,
                  "repostCount": 0,
                  "likeCount": 0,
                  "quoteCount": 0,
                  "indexedAt": "2026-07-30T07:00:01Z"
                }
                """, PostView.class);

        var withMedia = assertInstanceOf(
                Embed.RecordWithMediaView.class, post.embed());
        assertInstanceOf(Embed.ImagesView.class, withMedia.media());
        var quotedView = assertInstanceOf(
                Embed.ViewRecord.class, withMedia.record().record());
        assertInstanceOf(
                Embed.ImagesView.class, quotedView.embeds().getFirst());

        var generic = post.toGenericPost(null, new Site("bluesky"));
        assertEquals(1, generic.attachments().size());
        assertNotNull(generic.quotedPost());
        assertEquals("https://bsky.app/profile/bob.example/post/quoted",
                generic.quotedPost().url());
        assertEquals(1, generic.quotedPost().attachments().size());
        assertEquals("https://cdn.example/quoted.jpg",
                generic.quotedPost().attachments().getFirst().url());
    }

    @Test
    void acceptsUnknownEmbedVariantsWithoutACustomDeserializer()
            throws Exception {
        var embed = SocialJson.mapper.readValue("""
                {
                  "$type": "app.example.embed.future#view",
                  "label": "future value",
                  "payload": {
                    "fullsize": "https://cdn.example/future.jpg",
                    "count": 7
                  }
                }
                """, Embed.class);

        var unknown = assertInstanceOf(
                Embed.UnknownEmbedView.class, embed);
        assertEquals("future value", unknown.fields().get("label").asText());
        assertEquals(
                "https://cdn.example/future.jpg",
                unknown.mediaUrls(true).getFirst());
        assertEquals(7,
                unknown.fields().get("payload").get("count").asInt());
    }

    @Test
    void preservesUnknownQuotedRecordVariants() throws Exception {
        var embed = SocialJson.mapper.readValue("""
                {
                  "$type": "app.bsky.embed.record#view",
                  "record": {
                    "$type": "app.bsky.embed.record#viewBlocked",
                    "uri": "at://did:plc:bob/app.bsky.feed.post/blocked",
                    "blocked": true,
                    "author": {
                      "did": "did:plc:bob"
                    }
                  }
                }
                """, Embed.class);

        var record = assertInstanceOf(
                Embed.RecordView.class,
                embed);
        var unknown = assertInstanceOf(
                Embed.UnknownRecordView.class, record.record());
        assertEquals(
                "at://did:plc:bob/app.bsky.feed.post/blocked",
                unknown.uri());
        assertEquals(true, unknown.fields().get("blocked").asBoolean());
    }
}
