package org.netpreserve.warcana.bluesky;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcana.bluesky.model.FeedPage;
import org.netpreserve.warcana.bluesky.model.FeedItem;
import org.netpreserve.warcana.bluesky.model.Profile;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.SocialJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlueskyAccountArchiverTest {
    @Test
    void resolvesHandleToDidAndPaginatesAuthorFeed() throws Exception {
        var api = new FakeApi();
        var media = new RecordingMediaSink();
        var progress = new ArrayList<String>();
        var archiver = new BlueskyAccountArchiver(api, 100, media, progress::add);

        var result = archiver.archive("alice.example");

        assertEquals("alice.example", api.profileActor);
        assertEquals(List.of("did:plc:example", "did:plc:example"), api.feedActors);
        assertEquals(Arrays.asList(null, "next-page"), api.cursors);
        assertEquals(3, result.feedItemsArchived());
        assertEquals(2, result.pagesFetched());
        assertEquals("alice.example", result.profile().handle());
        assertEquals(3, result.media().captured());
        assertEquals(List.of(
                        "https://cdn.example/avatar.png",
                        "https://cdn.example/image.jpg",
                        "https://cdn.example/image-thumb.jpg"),
                List.copyOf(media.urls));
        assertEquals(List.of(
                        "Looking up Bluesky account: alice.example",
                        "Found Bluesky account: alice.example",
                        "Fetching Bluesky feed page 1...",
                        "Fetched 2 feed items (2 total)",
                        "Fetching Bluesky feed page 2...",
                        "Fetched 1 feed item (3 total)"),
                progress);
    }

    @Test
    void deserializesPublicAppViewResponses() throws Exception {
        Profile profile = SocialJson.mapper.readValue("""
                {
                  "did": "did:plc:example",
                  "handle": "alice.example",
                  "displayName": "Alice Example",
                  "followersCount": 10,
                  "followsCount": 20,
                  "postsCount": 30
                }
                """, Profile.class);
        FeedPage page = SocialJson.mapper.readValue("""
                {"cursor":"next-page","feed":[{"post":{"uri":"at://did:plc:example/app.bsky.feed.post/1"}}]}
                """, FeedPage.class);

        assertEquals("did:plc:example", profile.did());
        assertEquals("next-page", page.cursor());
        assertEquals(1, page.feed().size());
    }

    private static class FakeApi implements BlueskyApi {
        String profileActor;
        List<String> feedActors = new ArrayList<>();
        List<String> cursors = new ArrayList<>();

        @Override
        public Profile getProfile(String actor) {
            profileActor = actor;
            return new Profile(
                    "did:plc:example",
                    "alice.example",
                    "Alice Example",
                    null,
                    "https://cdn.example/avatar.png",
                    null,
                    0,
                    0,
                    3);
        }

        @Override
        public FeedPage getAuthorFeed(String actor, String cursor, int limit) throws IOException {
            feedActors.add(actor);
            cursors.add(cursor);
            if (cursor == null) {
                return new FeedPage("next-page", List.of(
                        feedItem("one"),
                        feedItem("two")));
            }
            return new FeedPage(null, List.of(feedItem("three")));
        }

        private FeedItem feedItem(String recordKey) throws IOException {
            return SocialJson.mapper.readValue("""
                    {
                      "post": {
                        "uri": "at://did:plc:example/app.bsky.feed.post/%s",
                        "cid": "cid-%s",
                        "author": {
                          "did": "did:plc:example",
                          "handle": "alice.example"
                        },
                        "record": {
                          "$type": "app.bsky.feed.post",
                          "text": "Example",
                          "createdAt": "2026-07-30T07:00:00Z"
                        },
                        "embed": {
                          "$type": "app.bsky.embed.images#view",
                          "images": [{
                            "fullsize": "https://cdn.example/image.jpg",
                            "thumb": "https://cdn.example/image-thumb.jpg",
                            "alt": "Example image"
                          }]
                        },
                        "replyCount": 0,
                        "repostCount": 0,
                        "likeCount": 0,
                        "quoteCount": 0,
                        "indexedAt": "2026-07-30T07:00:01Z"
                      }
                    }
                    """.formatted(recordKey, recordKey), FeedItem.class);
        }
    }

    private static class RecordingMediaSink implements MediaSink {
        private final LinkedHashSet<String> urls = new LinkedHashSet<>();

        @Override
        public void captureAll(Collection<String> urls) {
            this.urls.addAll(urls);
        }

        @Override
        public MediaCaptureStats stats() {
            return new MediaCaptureStats(urls.size(), 0, 0, 0);
        }
    }
}
