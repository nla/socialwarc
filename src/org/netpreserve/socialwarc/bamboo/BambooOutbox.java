package org.netpreserve.socialwarc.bamboo;

import org.netpreserve.socialwarc.common.SocialJson;
import org.netpreserve.socialwarc.common.collection.CollectionStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BambooOutbox {
    private final CollectionStore store;
    private final BambooConfiguration configuration;

    public BambooOutbox(
            CollectionStore store, BambooConfiguration configuration) {
        this.store = store;
        this.configuration = configuration;
    }

    public synchronized BambooOutboxItem enqueue(List<Path> warcs)
            throws IOException {
        if (warcs.isEmpty()) {
            return null;
        }
        Files.createDirectories(directory());
        var item = new BambooOutboxItem();
        item.id = "socialwarc-" + UUID.randomUUID();
        item.seriesId = configuration.ingestSeriesId();
        item.createdAt = Instant.now().toString();
        item.name = "SocialWARC capture " + DateTimeFormatter
                .ofPattern("uuuu-MM-dd HH:mm 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.parse(item.createdAt));
        item.description = "WARC files captured by SocialWARC. Ingest batch " +
                item.id + ".";
        for (Path path : warcs.stream().sorted().toList()) {
            Path absolute = path.toAbsolutePath().normalize();
            if (!absolute.startsWith(store.warcsDirectory()) ||
                    !Files.isRegularFile(absolute)) {
                throw new IOException(
                        "Bamboo ingest file is outside the collection: " + path);
            }
            var file = new BambooOutboxItem.File();
            file.path = store.root().relativize(absolute).toString();
            file.filename = absolute.getFileName().toString();
            file.size = Files.size(absolute);
            file.sha256 = sha256(absolute);
            item.files.add(file);
        }
        save(item);
        store.log("BAMBOO-QUEUED " + item.id + " warcs=" +
                item.files.size());
        return item;
    }

    /**
     * Queues completed WARCs that are not already represented by any durable
     * outbox receipt. This closes the crash window between publishing a WARC
     * and recording its ingest batch.
     */
    public synchronized BambooOutboxItem enqueueMissing(List<Path> warcs)
            throws IOException {
        Set<String> queued = new HashSet<>();
        for (BambooOutboxItem item : items()) {
            for (BambooOutboxItem.File file : item.files) {
                queued.add(file.path);
            }
        }
        List<Path> missing = warcs.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> !queued.contains(
                        store.root().relativize(path).toString()))
                .toList();
        return enqueue(missing);
    }

    public List<BambooOutboxItem> pending() throws IOException {
        return items().stream()
                .filter(item -> !"complete".equals(item.status))
                .toList();
    }

    private List<BambooOutboxItem> items() throws IOException {
        if (!Files.isDirectory(directory())) {
            return List.of();
        }
        try (var paths = Files.list(directory())) {
            return paths
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".json"))
                    .sorted()
                    .map(path -> {
                        try {
                            BambooOutboxItem item = SocialJson.mapper.readValue(
                                    path.toFile(), BambooOutboxItem.class);
                            if (item.version != BambooOutboxItem.CURRENT_VERSION) {
                                throw new IOException(
                                        "Unsupported Bamboo outbox version " +
                                                item.version + ": " + path);
                            }
                            return item;
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public synchronized void save(BambooOutboxItem item) throws IOException {
        Files.createDirectories(directory());
        Path destination = path(item.id);
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp-" + UUID.randomUUID());
        SocialJson.mapper.writerWithDefaultPrettyPrinter()
                .writeValue(temporary.toFile(), item);
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path resolve(BambooOutboxItem.File file) throws IOException {
        Path path = store.root().resolve(file.path).normalize();
        if (!path.startsWith(store.warcsDirectory()) ||
                !Files.isRegularFile(path)) {
            throw new IOException("Queued WARC is missing: " + file.path);
        }
        if (Files.size(path) != file.size) {
            throw new IOException("Queued WARC size changed: " + file.path);
        }
        return path;
    }

    private Path directory() {
        return store.stateDirectory().resolve("bamboo-outbox");
    }

    private Path path(String id) {
        return directory().resolve(id + ".json");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
