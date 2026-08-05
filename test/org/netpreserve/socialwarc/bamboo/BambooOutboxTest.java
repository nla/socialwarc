package org.netpreserve.socialwarc.bamboo;

import org.junit.jupiter.api.Test;
import org.netpreserve.socialwarc.common.collection.CollectionStore;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BambooOutboxTest {
    @Test
    void persistsCompletedWarcsForReliableIngest() throws Exception {
        Path root = Files.createTempDirectory("socialwarc-bamboo-outbox-");
        try {
            var store = new CollectionStore(root);
            store.initialize();
            Path warc = store.warcsDirectory().resolve("run.warc");
            Files.writeString(warc, "immutable WARC bytes");
            var configuration = new BambooConfiguration(
                    URI.create("https://bamboo.example/"),
                    123L, List.of(), null);
            var outbox = new BambooOutbox(store, configuration);

            BambooOutboxItem queued = outbox.enqueue(List.of(warc));
            assertNotNull(queued);
            assertEquals(123L, queued.seriesId);
            assertEquals(1, queued.files.size());
            assertEquals("run.warc", queued.files.get(0).filename);
            assertEquals(64, queued.files.get(0).sha256.length());

            BambooOutboxItem reloaded = outbox.pending().get(0);
            assertEquals(queued.id, reloaded.id);
            assertEquals(warc, outbox.resolve(reloaded.files.get(0)));

            reloaded.status = "complete";
            outbox.save(reloaded);
            assertEquals(List.of(), outbox.pending());
            assertNull(outbox.enqueueMissing(List.of(warc)));

            Path recovered = store.warcsDirectory().resolve("recovered.warc");
            Files.writeString(recovered, "published before receipt");
            BambooOutboxItem reconciliation = outbox.enqueueMissing(
                    List.of(warc, recovered));
            assertNotNull(reconciliation);
            assertEquals(List.of("recovered.warc"), reconciliation.files
                    .stream().map(file -> file.filename).toList());
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
