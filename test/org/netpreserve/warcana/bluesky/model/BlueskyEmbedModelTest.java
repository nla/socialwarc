package org.netpreserve.warcana.bluesky.model;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.model.Site;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlueskyEmbedModelTest {
    @Test
    void preservesReplyParentAndThreadRoot() {
        var author = new Author(
                "did:plc:alice", "alice.example", "Alice", null, null);
        var parentAuthor = new Author(
                "did:plc:bob", "bob.example", "Bob", null, null);
        var rootAuthor = new Author(
                "did:plc:carol", "carol.example", "Carol", null, null);
        var view = new PostView(
                "at://did:plc:alice/app.bsky.feed.post/reply",
                "reply-cid",
                author,
                new PostRecord(
                        "app.bsky.feed.post",
                        "A reply",
                        Instant.parse("2026-07-30T07:00:00Z"),
                        null,
                        null),
                null, 0, 0, 0, 0,
                Instant.parse("2026-07-30T07:00:01Z"));
        var reply = new FeedItem.ReplyContext(
                new FeedItem.ReplyPost(
                        "at://did:plc:carol/app.bsky.feed.post/root",
                        rootAuthor),
                new FeedItem.ReplyPost(
                        "at://did:plc:bob/app.bsky.feed.post/parent",
                        parentAuthor));

        var generic = view.toGenericPost(reply, new Site("Bluesky"));

        assertEquals(view.uri(), generic.id());
        assertEquals(reply.parent().uri(), generic.thread().parent().id());
        assertEquals(
                "https://bsky.app/profile/bob.example/post/parent",
                generic.thread().parent().url());
        assertEquals(reply.root().uri(), generic.thread().root().id());
    }

    @Test
    void preservesExternalLinkPreviewMetadata() throws Exception {
        var embed = SocialJson.mapper.readValue("""
                {
                  "$type": "app.bsky.embed.external#view",
                  "external": {
                    "uri": "https://example.test/story",
                    "title": "Example story",
                    "description": "A synthetic preview description",
                    "thumb": "https://media.example/preview.jpg"
                  }
                }
                """, Embed.class);

        var attachment = embed.attachments().getFirst();
        assertEquals("link", attachment.type());
        assertEquals("Example story", attachment.title());
        assertEquals(
                "A synthetic preview description",
                attachment.description());
        assertEquals(
                "https://media.example/preview.jpg",
                attachment.thumbnailUrl());
    }

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
                    "text": "Outer link",
                    "facets": [{
                      "index": {"byteStart": 6, "byteEnd": 10},
                      "features": [{
                        "$type": "app.bsky.richtext.facet#link",
                        "uri": "https://outer.example"
                      }]
                    }],
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
                          "text": "Quoted link",
                          "facets": [{
                            "index": {"byteStart": 7, "byteEnd": 11},
                            "features": [{
                              "$type": "app.bsky.richtext.facet#link",
                              "uri": "https://quoted.example"
                            }]
                          }],
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
        assertEquals(
                "Outer <a href=\"https://outer.example\">link</a>",
                generic.content());
        assertNotNull(generic.quotedPost());
        assertEquals(
                "Quoted <a href=\"https://quoted.example\">link</a>",
                generic.quotedPost().content());
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

    @Test
    void acceptsRecordWithMediaWrapperWithoutNestedTypeId() throws Exception {
        var embed = SocialJson.mapper.readValue("""
                {
                  "$type": "app.bsky.embed.recordWithMedia#view",
                  "media": {
                    "$type": "app.bsky.embed.images#view",
                    "images": [{
                      "thumb": "https://cdn.example/thumb.jpg",
                      "fullsize": "https://cdn.example/image.jpg",
                      "alt": "Example"
                    }]
                  },
                  "record": {
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
                      "embeds": [],
                      "replyCount": 0,
                      "repostCount": 0,
                      "likeCount": 0,
                      "quoteCount": 0,
                      "indexedAt": "2026-07-29T07:00:01Z"
                    }
                  }
                }
                """, Embed.class);

        var withMedia = assertInstanceOf(
                Embed.RecordWithMediaView.class, embed);
        assertInstanceOf(
                Embed.ViewRecord.class, withMedia.record().record());
        assertEquals(1, withMedia.attachments().size());
        assertNotNull(withMedia.quotedPost(new Site("bluesky")));
    }
}
