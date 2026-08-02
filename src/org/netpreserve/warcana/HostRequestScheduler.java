package org.netpreserve.warcana;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Serializes HTTP captures to each remote server. */
final class HostRequestScheduler {
    @FunctionalInterface
    interface IoCall<T> {
        T run() throws IOException;
    }

    private final ConcurrentHashMap<String, Semaphore> servers =
            new ConcurrentHashMap<>();

    <T> T execute(URI uri, IoCall<T> call) throws IOException {
        String key = serverKey(uri);
        Semaphore semaphore = servers.computeIfAbsent(
                key, ignored -> new Semaphore(1, true));
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting to contact " + key, e);
        }
        try {
            return call.run();
        } finally {
            semaphore.release();
        }
    }

    private static String serverKey(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" +
                uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
