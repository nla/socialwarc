package org.netpreserve.warcana.common.resource;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.NIOFSDirectory;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.source.LocalWarcSource;
import org.netpreserve.warcana.common.source.WarcSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent URL-to-WARC-offset lookup for archived resources.
 */
public class WarcResourceIndex {
    private static final String SCHEMA_VERSION = "1";
    private static final String META_SCHEMA = "warcana.resource.schema";
    private static final String META_SOURCES = "warcana.resource.sources";
    private static final String FIELD_ID = "id";
    private static final String FIELD_URL = "url";
    private static final String FIELD_WARC = "warc";
    private static final String FIELD_OFFSET = "offset";
    private static final String FIELD_RECORD_ID = "record_id";
    private static final String FIELD_CONTENT_TYPE = "content_type";

    public boolean ensureCurrent(
            List<Path> warcs, Path index, boolean rebuild,
            ProgressReporter progress) throws IOException {
        return ensureCurrentSources(
                warcs.stream().map(LocalWarcSource::new).toList(),
                index, rebuild, progress);
    }

    public boolean ensureCurrentSources(
            List<? extends WarcSource> warcs, Path index, boolean rebuild,
            ProgressReporter progress) throws IOException {
        if (!rebuild && isCurrent(warcs, index)) {
            progress.report("Using resource index: " + index.toAbsolutePath());
            return false;
        }
        if (Files.exists(index) && !Files.isDirectory(index)) {
            throw new IOException("Resource index path is not a directory: " + index);
        }
        if (Files.isSymbolicLink(index)) {
            throw new IOException("Resource index path must not be a symbolic link: " + index);
        }

        Path absoluteIndex = index.toAbsolutePath();
        Files.createDirectories(absoluteIndex.getParent());
        Path temporary = absoluteIndex.resolveSibling(
                absoluteIndex.getFileName() + ".tmp-" + UUID.randomUUID());
        progress.report("Building resource index: " + absoluteIndex);
        try {
            Files.createDirectories(temporary);
            build(warcs, temporary, progress);
            if (Files.exists(absoluteIndex)) {
                deleteTree(absoluteIndex);
            }
            try {
                Files.move(temporary, absoluteIndex, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absoluteIndex);
            }
            return true;
        } finally {
            if (Files.exists(temporary)) {
                deleteTree(temporary);
            }
        }
    }

    public ArchivedResource find(Path index, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}") || !Files.isDirectory(index)) {
            return null;
        }
        try (var directory = new NIOFSDirectory(index)) {
            if (!DirectoryReader.indexExists(directory)) {
                return null;
            }
            try (var reader = DirectoryReader.open(directory)) {
                var searcher = new IndexSearcher(reader);
                var results = searcher.search(
                        new TermQuery(new Term(FIELD_ID, id)), 1);
                if (results.scoreDocs.length == 0) {
                    return null;
                }
                return fromDocument(searcher.storedFields()
                        .document(results.scoreDocs[0].doc));
            }
        }
    }

    public Map<String, String> idsForUrls(Path index, Iterable<String> urls)
            throws IOException {
        var requested = new ArrayList<String>();
        for (String url : urls) {
            if (url != null && !url.isBlank()) {
                requested.add(url);
            }
        }
        if (requested.isEmpty() || !Files.isDirectory(index)) {
            return Map.of();
        }
        var found = new LinkedHashMap<String, String>();
        try (var directory = new NIOFSDirectory(index)) {
            if (!DirectoryReader.indexExists(directory)) {
                return Map.of();
            }
            try (var reader = DirectoryReader.open(directory)) {
                var searcher = new IndexSearcher(reader);
                for (String url : requested) {
                    String id = idForUrl(url);
                    var results = searcher.search(
                            new TermQuery(new Term(FIELD_ID, id)), 1);
                    if (results.scoreDocs.length > 0) {
                        found.put(url, id);
                    }
                }
            }
        }
        return Map.copyOf(found);
    }

    public static String idForUrl(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void build(
            List<? extends WarcSource> warcs, Path index,
            ProgressReporter progress)
            throws IOException {
        try (var directory = new NIOFSDirectory(index);
             var analyzer = new StandardAnalyzer();
             var writer = new IndexWriter(
                     directory,
                     new IndexWriterConfig(analyzer)
                             .setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            Set<String> seen = new HashSet<>();
            long count = 0;
            for (WarcSource warc : warcs) {
                String filename = warc.filename();
                boolean resourceWarc =
                        filename.startsWith("warcana-media-") ||
                                filename.startsWith("warcana-video-");
                try (var reader = new WarcReader(warc.open())) {
                    while (true) {
                        var next = reader.next();
                        if (next.isEmpty()) {
                            break;
                        }
                        if (!(next.get() instanceof WarcResponse response)) {
                            continue;
                        }
                        String url = response.target().toString();
                        if (seen.contains(url)) {
                            continue;
                        }
                        var http = response.http();
                        if (http.status() < 200 || http.status() >= 300) {
                            continue;
                        }
                        String contentType = http.headers()
                                .first("Content-Type")
                                .orElse("application/octet-stream");
                        if (!resourceWarc && !looksLikeMedia(contentType)) {
                            continue;
                        }
                        seen.add(url);
                        String id = idForUrl(url);
                        var document = new Document();
                        document.add(new StringField(
                                FIELD_ID, id, Field.Store.YES));
                        document.add(new StoredField(FIELD_URL, url));
                        document.add(new StoredField(
                                FIELD_WARC, warc.id()));
                        document.add(new StoredField(
                                FIELD_OFFSET, response.position()));
                        document.add(new StoredField(
                                FIELD_RECORD_ID, response.id().toString()));
                        document.add(new StoredField(
                                FIELD_CONTENT_TYPE, contentType));
                        writer.addDocument(document);
                        count++;
                    }
                }
            }
            writer.setLiveCommitData(metadata(warcs).entrySet());
            writer.commit();
            progress.report("Indexed " + count + " archived resources");
        }
    }

    private static ArchivedResource fromDocument(Document document) {
        return new ArchivedResource(
                document.get(FIELD_ID),
                document.get(FIELD_URL),
                document.get(FIELD_WARC),
                document.getField(FIELD_OFFSET).numericValue().longValue(),
                document.get(FIELD_RECORD_ID),
                document.get(FIELD_CONTENT_TYPE));
    }

    private static boolean looksLikeMedia(String contentType) {
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("image/") ||
                normalized.startsWith("audio/") ||
                normalized.startsWith("video/") ||
                normalized.startsWith("application/octet-stream") ||
                normalized.startsWith("application/vnd.apple.mpegurl") ||
                normalized.startsWith("application/x-mpegurl");
    }

    private static boolean isCurrent(
            List<? extends WarcSource> warcs, Path index) {
        if (!Files.isDirectory(index)) {
            return false;
        }
        try (var directory = new NIOFSDirectory(index)) {
            if (!DirectoryReader.indexExists(directory)) {
                return false;
            }
            try (var reader = DirectoryReader.open(directory)) {
                return reader.getIndexCommit().getUserData()
                        .equals(metadata(warcs));
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Map<String, String> metadata(
            List<? extends WarcSource> warcs)
            throws IOException {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put(META_SCHEMA, SCHEMA_VERSION);
        var sources = new StringBuilder();
        for (WarcSource source : warcs) {
            sources.append(source.id())
                    .append('\t')
                    .append(source.size())
                    .append('\t')
                    .append(source.version())
                    .append('\n');
        }
        metadata.put(META_SOURCES, sources.toString());
        return metadata;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
