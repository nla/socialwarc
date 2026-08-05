package org.netpreserve.socialwarc.mastodon;

import org.junit.jupiter.api.Test;
import org.netpreserve.socialwarc.common.ArchiveCheckpoint;
import org.netpreserve.socialwarc.common.ArchiveRunOptions;
import org.netpreserve.socialwarc.common.SocialJson;
import org.netpreserve.socialwarc.mastodon.model.Account;
import org.netpreserve.socialwarc.mastodon.model.Status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MastodonLocalTimelineArchiverTest {
    @Test
    void pagesBackwardThroughLocalTimelineAndCheckpoints() throws Exception {
        var api = new FakeApi();
        var checkpoints = new ArrayList<ArchiveCheckpoint.PageCheckpoint>();
        var progress = new ArrayList<String>();
        var archiver = new MastodonLocalTimelineArchiver(
                api, 40, null, progress::add, true);

        var result = archiver.archive(
                ArchiveRunOptions.unbounded(), checkpoints::add);

        assertEquals(java.util.Arrays.asList(null, "200", "100"), api.maxIds);
        assertTrue(api.localValues.stream().allMatch(Boolean::booleanValue));
        assertEquals(3, result.statusesArchived());
        assertEquals(3, result.pagesFetched());
        assertEquals("300", result.newestIdentity());
        assertTrue(result.complete());
        assertEquals("200", checkpoints.get(0).cursor());
        assertEquals("100", checkpoints.get(1).cursor());
        assertEquals(null, checkpoints.get(2).cursor());
        assertTrue(checkpoints.get(2).complete());
    }

    @Test
    void incrementalRunStopsAtPreviousHighWaterMark() throws Exception {
        var api = new FakeApi();
        var checkpoints = new ArrayList<ArchiveCheckpoint.PageCheckpoint>();
        var archiver = new MastodonLocalTimelineArchiver(
                api, 40, null, ignored -> { }, true);

        var result = archiver.archive(
                new ArchiveRunOptions(
                        null, null, 0, 0, "200",
                        null, null, 0, 0),
                checkpoints::add);

        assertEquals(1, result.statusesArchived());
        assertEquals(1, result.pagesFetched());
        assertEquals("300", result.newestIdentity());
        assertTrue(result.complete());
        assertEquals(null, checkpoints.getFirst().cursor());
    }

    private static class FakeApi implements MastodonApi {
        final List<String> maxIds = new ArrayList<>();
        final List<Boolean> localValues = new ArrayList<>();

        @Override
        public Account lookupAccount(String acct) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Status> getAccountStatuses(
                String accountId, String sinceId, String maxId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Status> getPublicTimeline(
                boolean local, String minId, String maxId, int limit)
                throws IOException {
            localValues.add(local);
            maxIds.add(maxId);
            assertEquals(null, minId);
            assertEquals(40, limit);
            if (maxId == null) return List.of(status("300"), status("200"));
            if (maxId.equals("200")) return List.of(status("100"));
            return List.of();
        }

        private Status status(String id) throws IOException {
            return SocialJson.mapper.readValue("""
                    {
                      "id":"%s",
                      "uri":"https://example.social/users/alice/statuses/%s",
                      "created_at":"2026-08-01T00:00:00Z",
                      "account":{},
                      "content":"",
                      "visibility":"public",
                      "sensitive":false,
                      "spoiler_text":"",
                      "media_attachments":[],
                      "mentions":[],
                      "tags":[],
                      "emojis":[],
                      "reblogs_count":0,
                      "favourites_count":0,
                      "replies_count":0
                    }
                    """.formatted(id, id), Status.class);
        }
    }
}
