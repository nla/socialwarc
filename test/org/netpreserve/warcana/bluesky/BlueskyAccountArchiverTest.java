package org.netpreserve.warcana.bluesky;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcana.common.SocialJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlueskyAccountArchiverTest {
    @Test
    void resolvesHandleToDidAndPaginatesAuthorFeed() throws Exception {
        var api = new FakeApi();
        var archiver = new BlueskyAccountArchiver(api, 100);

        var result = archiver.archive("alice.example");

        assertEquals("alice.example", api.profileActor);
        assertEquals(List.of("did:plc:example", "did:plc:example"), api.feedActors);
        assertEquals(Arrays.asList(null, "next-page"), api.cursors);
        assertEquals(3, result.feedItemsArchived());
        assertEquals(2, result.pagesFetched());
        assertEquals("alice.example", result.profile().handle());
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
                    null,
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
                        SocialJson.mapper.createObjectNode(),
                        SocialJson.mapper.createObjectNode()));
            }
            return new FeedPage(null, List.of(SocialJson.mapper.createObjectNode()));
        }
    }
}
