package org.netpreserve.socialwarc;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRequestSchedulerTest {
    @Test
    void serializesEachServerButAllowsDifferentServersInParallel()
            throws Exception {
        var scheduler = new HostRequestScheduler();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> scheduler.execute(
                        URI.create("https://one.example/api/"), () -> {
                            int now = active.incrementAndGet();
                            maximum.accumulateAndGet(now, Math::max);
                            try {
                                sleep(Duration.ofMillis(10));
                            } finally {
                                active.decrementAndGet();
                            }
                            return null;
                        })));
            }
            for (var future : futures) {
                future.get();
            }
            assertEquals(1, maximum.get());

            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var first = executor.submit(() -> scheduler.execute(
                    URI.create("https://one.example/api/"), () -> {
                        entered.countDown();
                        await(release);
                        return null;
                    }));
            var second = executor.submit(() -> scheduler.execute(
                    URI.create("https://two.example/api/"), () -> {
                        entered.countDown();
                        await(release);
                        return null;
                    }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            first.get();
            second.get();
        }
    }

    private static void await(CountDownLatch latch) throws java.io.IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new java.io.IOException("Timed out waiting in test");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Interrupted in test", e);
        }
    }

    private static void sleep(Duration duration) throws java.io.IOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Interrupted in test", e);
        }
    }
}
