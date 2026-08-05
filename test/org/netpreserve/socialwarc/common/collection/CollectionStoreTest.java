package org.netpreserve.socialwarc.common.collection;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.socialwarc.common.ProgressReporter;

import java.nio.file.Files;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionStoreTest {
    @Test
    void initializesParsesAccountsAndPersistsJobsAtomically() throws Exception {
        var root = Files.createTempDirectory("socialwarc-collection-");
        try {
            var store = new CollectionStore(root);
            store.initialize();

            assertTrue(Files.isRegularFile(root.resolve("accounts.txt")));
            assertTrue(Files.isDirectory(root.resolve("warcs")));
            assertTrue(Files.isDirectory(root.resolve("logs")));
            assertTrue(Files.isDirectory(root.resolve("state/jobs")));
            Files.writeString(store.accountsFile(), """
                    # collecting scope
                    mastodon @alice@example.social
                    mastodon-local example.social
                    bluesky alice.bsky.social
                    @bob@elsewhere.example
                    """);

            var accounts = store.readAccounts();
            assertEquals(4, accounts.size());
            assertEquals("mastodon", accounts.get(0).platform());
            assertEquals("@alice@example.social", accounts.get(0).account());
            assertEquals("mastodon-local", accounts.get(1).platform());
            assertTrue(accounts.get(1).localTimeline());
            assertEquals("bluesky", accounts.get(2).platform());
            assertEquals(null, accounts.get(3).platform());

            var job = new CollectionJob();
            job.key = CollectionStore.accountKey("mastodon", accounts.get(0).account());
            job.account = accounts.get(0).account();
            job.platform = "mastodon";
            job.posts.setPaths(
                    "warcs/example.warc.open",
                    "warcs/example.warc");
            job.posts.setCommittedOffset(1234);
            job.pendingMedia.add("https://example.social/image.jpg");
            store.saveJob(job);

            var loaded = store.loadJob(job.key);
            assertEquals(1234, loaded.posts.committedOffset());
            assertEquals(job.pendingMedia, loaded.pendingMedia);

            Files.write(root.resolve("warcs/legacy.warc"), new byte[]{1});
            Files.write(root.resolve("warcs/posts.warc.gz"), new byte[]{2});
            Files.write(root.resolve("warcs/ignored.txt"), new byte[]{3});
            assertEquals(2, store.completedWarcs().size());
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    void rejectsUnsupportedPersistedSchemaVersions() throws Exception {
        var root = Files.createTempDirectory("socialwarc-schema-version-");
        try {
            var store = new CollectionStore(root);
            store.initialize();
            Files.writeString(
                    store.collectionStateFile(), "{\"version\":1}");
            assertThrows(
                    java.io.IOException.class,
                    store::loadCollectionState);

            Files.writeString(
                    store.jobFile("old-job"), "{\"version\":1}");
            assertThrows(
                    java.io.IOException.class,
                    () -> store.loadJob("old-job"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path :
                        paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    void recognizesCurrentResourceWarcFilenames() throws Exception {
        var root = Files.createTempDirectory("socialwarc-filenames-");
        try {
            var store = new CollectionStore(root);
            store.initialize();
            Files.write(root.resolve("warcs/socialwarc-posts-new.warc"),
                    new byte[]{1});
            Files.write(root.resolve("warcs/socialwarc-media-new.warc"),
                    new byte[]{1});

            assertEquals(2, store.completedWarcs().size());
            assertEquals(1, store.completedPostWarcs().size());
            assertTrue(CollectionWarcStream.MEDIA.matchesFilename(
                    "socialwarc-media-new.warc"));
            assertTrue(CollectionWarcStream.VIDEO.matchesFilename(
                    "socialwarc-video-new.warc"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path :
                        paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    void resumesAndCheckpointsAPersistedMediaQueue() throws Exception {
        var root = Files.createTempDirectory("socialwarc-media-resume-");
        try {
            var store = new CollectionStore(root);
            store.initialize();
            var job = new CollectionJob();
            job.key = "resume-test";
            job.account = "@alice@example.social";
            job.platform = "mastodon";
            job.posts.setPaths(
                    "warcs/resume.warc.open",
                    "warcs/resume.warc");
            job.pendingMedia.add("file:///unsafe-but-durable");
            store.saveJob(job);

            var open = root.resolve(job.posts.openWarc());
            try (var writer = new WarcWriter(open)) {
                writer.write(new Warcinfo.Builder()
                        .filename("resume.warc")
                        .build());
                job.posts.setCommittedOffset(writer.position());
                store.saveJob(job);
                var sink = new CheckpointingMediaSink(
                        store,
                        job,
                        writer,
                        "socialwarc-test",
                        1024,
                        4096,
                        false,
                        ProgressReporter.NONE);

                sink.drainPending();
            }

            var resumed = store.loadJob(job.key);
            assertTrue(resumed.pendingMedia.isEmpty());
            assertTrue(resumed.seenMedia.contains("file:///unsafe-but-durable"));
            assertEquals(1, resumed.mediaSkipped);
            assertTrue(resumed.posts.committedOffset() > 0);
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    void checkpointsMediaAndVideoToIndependentWriters() throws Exception {
        var root = Files.createTempDirectory("socialwarc-split-media-");
        try {
            var store = new CollectionStore(root);
            store.initialize();
            var job = new CollectionJob();
            job.key = "split-test";
            store.saveJob(job);
            var media = root.resolve("warcs/socialwarc-media-test.warc.open");
            var video = root.resolve("warcs/socialwarc-video-test.warc.open");

            try (var sink = new SplitCheckpointingMediaSink(
                    store,
                    job,
                    () -> new WarcWriter(media),
                    () -> new WarcWriter(video),
                    "socialwarc-test",
                    1024,
                    4096,
                    false,
                    ProgressReporter.NONE)) {
                sink.discover(java.util.List.of("file:///image.jpg"));
                sink.discoverVideo(java.util.List.of("file:///video.mp4"));
                sink.drain();
            }

            var resumed = store.loadJob(job.key);
            assertTrue(resumed.pendingMedia.isEmpty());
            assertTrue(resumed.pendingVideo.isEmpty());
            assertTrue(resumed.seenMedia.contains("file:///image.jpg"));
            assertTrue(resumed.seenVideo.contains("file:///video.mp4"));
            assertEquals(1, resumed.mediaSkipped);
            assertEquals(1, resumed.videoSkipped);
            assertTrue(Files.exists(media));
            assertTrue(Files.exists(video));
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
