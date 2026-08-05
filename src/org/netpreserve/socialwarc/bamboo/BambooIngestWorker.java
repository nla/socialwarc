package org.netpreserve.socialwarc.bamboo;

import org.netpreserve.socialwarc.common.collection.CollectionStore;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Reliably drains completed collection WARCs into Bamboo. */
public final class BambooIngestWorker implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final CollectionStore store;
    private final BambooOutbox outbox;
    private final BambooClient client;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("socialwarc-bamboo-ingest").factory());

    public BambooIngestWorker(
            CollectionStore store, BambooConfiguration configuration) {
        this.store = store;
        outbox = new BambooOutbox(store, configuration);
        client = new BambooClient(configuration);
    }

    public void start() {
        executor.scheduleWithFixedDelay(
                this::runSafely, 0, POLL_INTERVAL.toSeconds(),
                TimeUnit.SECONDS);
    }

    public void trigger() {
        executor.execute(this::runSafely);
    }

    public BambooOutbox outbox() {
        return outbox;
    }

    void runOnce() throws IOException, InterruptedException {
        List<BambooOutboxItem> items = outbox.pending();
        for (BambooOutboxItem item : items) {
            if (item.nextAttemptAt != null && Instant.parse(
                    item.nextAttemptAt).isAfter(Instant.now())) {
                continue;
            }
            process(item);
        }
    }

    private void process(BambooOutboxItem item)
            throws IOException, InterruptedException {
        try {
            item.status = "uploading";
            item.lastError = null;
            outbox.save(item);
            if (item.crawlId == null) {
                item.crawlId = client.findOrCreateCrawl(item);
                outbox.save(item);
                store.log("BAMBOO-CRAWL " + item.id + " crawl=" +
                        item.crawlId);
            }
            for (BambooOutboxItem.File file : item.files) {
                if (file.uploaded) {
                    continue;
                }
                var remote = client.ensureWarc(
                        item.crawlId, outbox.resolve(file), file.sha256);
                file.bambooWarcId = remote.id();
                file.uploaded = true;
                outbox.save(item);
                store.log("BAMBOO-WARC " + item.id + " " + file.filename +
                        " warc=" + remote.id());
            }
            item.status = "complete";
            item.completedAt = Instant.now().toString();
            item.nextAttemptAt = null;
            outbox.save(item);
            store.log("BAMBOO-COMPLETE " + item.id + " crawl=" +
                    item.crawlId);
        } catch (IOException e) {
            fail(item, e);
        }
    }

    private void fail(BambooOutboxItem item, IOException failure)
            throws IOException {
        item.attempts++;
        item.lastError = failure.getMessage();
        boolean blocked = failure instanceof BambooClient.BambooHttpException e &&
                e.status() >= 400 && e.status() < 500 &&
                e.status() != 408 && e.status() != 429;
        item.status = blocked ? "blocked" : "retrying";
        Duration delay = blocked
                ? Duration.ofHours(1)
                : backoff(item.attempts);
        item.nextAttemptAt = Instant.now().plus(delay).toString();
        outbox.save(item);
        store.log("BAMBOO-FAILED " + item.id + " " + item.status + " " +
                failure.getMessage());
    }

    private static Duration backoff(int attempts) {
        long minutes = 1L << Math.min(8, Math.max(0, attempts - 1));
        return Duration.ofMinutes(minutes).compareTo(MAX_BACKOFF) > 0
                ? MAX_BACKOFF
                : Duration.ofMinutes(minutes);
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            try {
                store.log("BAMBOO-WORKER-FAILED " + e.getMessage());
            } catch (IOException ignored) {
                // The worker will try again on its next poll.
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
