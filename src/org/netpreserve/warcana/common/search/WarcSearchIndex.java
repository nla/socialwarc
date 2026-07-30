package org.netpreserve.warcana.common.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.NIOFSDirectory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Entities;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.ScannedPost;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.WarcPostScanner;
import org.netpreserve.warcana.common.WarcResponseExtractor;
import org.netpreserve.warcana.common.model.Attachment;
import org.netpreserve.warcana.common.model.Post;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds and queries a persistent Lucene sidecar index for a WARC.
 */
public class WarcSearchIndex {
    private static final String SCHEMA_VERSION = "3";
    private static final String META_SCHEMA = "warcana.schema";
    private static final String META_SOURCE = "warcana.source";
    private static final String META_SIZE = "warcana.source.size";
    private static final String META_MODIFIED = "warcana.source.modified";

    private static final String FIELD_ID = "id";
    private static final String FIELD_PLATFORM = "platform";
    private static final String FIELD_AUTHOR = "author";
    private static final String FIELD_AUTHOR_EXACT = "author_exact";
    private static final String FIELD_MENTIONS = "mentions";
    private static final String FIELD_ALT = "alt";
    private static final String FIELD_URL = "url";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_CREATED = "created";
    private static final String FIELD_HAS_MEDIA = "has_media";
    private static final String FIELD_IDENTITY = "identity";
    private static final String FIELD_SOURCE_TARGET = "source_target";
    private static final String FIELD_WARC_RECORD_ID = "warc_record_id";
    private static final String FIELD_POST_JSON = "post_json";

    private static final FieldType HIGHLIGHTED_TEXT;

    static {
        Logger.getLogger(
                "org.apache.lucene.internal.vectorization.VectorizationProvider")
                .setLevel(Level.SEVERE);
        HIGHLIGHTED_TEXT = new FieldType(TextField.TYPE_STORED);
        HIGHLIGHTED_TEXT.setIndexOptions(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        HIGHLIGHTED_TEXT.freeze();
    }

    private final WarcPostScanner scanner;

    public WarcSearchIndex(List<WarcResponseExtractor> extractors) {
        scanner = new WarcPostScanner(extractors);
    }

    public boolean ensureCurrent(
            Path warc, Path index, boolean rebuild,
            ProgressReporter progress) throws IOException {
        if (!rebuild && isCurrent(warc, index)) {
            progress.report("Using search index: " + index.toAbsolutePath());
            return false;
        }
        if (Files.exists(index) && !Files.isDirectory(index)) {
            throw new IOException("Search index path is not a directory: " + index);
        }
        if (Files.isSymbolicLink(index)) {
            throw new IOException("Search index path must not be a symbolic link: " + index);
        }

        Path absoluteIndex = index.toAbsolutePath();
        Path parent = absoluteIndex.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = absoluteIndex.resolveSibling(
                absoluteIndex.getFileName() + ".tmp-" + UUID.randomUUID());
        progress.report("Building search index: " + absoluteIndex);

        try {
            Files.createDirectories(temporary);
            build(warc, temporary, progress);
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

    public SearchResults search(Path index, SearchOptions options) throws IOException {
        try (var directory = new NIOFSDirectory(index);
             var reader = DirectoryReader.open(directory);
             Analyzer analyzer = new StandardAnalyzer()) {
            var searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            Query query = buildQuery(options, analyzer);
            int totalHits = searcher.count(query);
            if (reader.maxDoc() == 0 || options.offset() >= totalHits) {
                return new SearchResults(totalHits, List.of());
            }
            int requested = (int) Math.min(
                    reader.maxDoc(),
                    (long) options.offset() + options.limit());

            boolean ranked = hasQuery(options);
            TopDocs topDocs;
            String[] snippets;
            if (ranked) {
                topDocs = searcher.search(query, requested);
                snippets = UnifiedHighlighter
                        .builder(searcher, analyzer)
                        .withFormatter(new DefaultPassageFormatter(
                                "<b>", "</b>", " … ", true))
                        .build()
                        .highlight(FIELD_TEXT, query, topDocs, 1);
            } else {
                var created = new SortField(
                        FIELD_CREATED, SortField.Type.LONG, true);
                created.setMissingValue(Long.MIN_VALUE);
                topDocs = searcher.search(
                        query,
                        requested,
                        new Sort(created, SortField.FIELD_DOC));
                snippets = null;
            }
            var storedFields = searcher.storedFields();
            var hits = new ArrayList<SearchHit>();
            for (int i = options.offset(); i < topDocs.scoreDocs.length; i++) {
                ScoreDoc scoreDoc = topDocs.scoreDocs[i];
                Document document = storedFields.document(scoreDoc.doc);
                Post post = SocialJson.mapper.readValue(
                        document.get(FIELD_POST_JSON), Post.class);
                hits.add(new SearchHit(
                        ranked ? scoreDoc.score : null,
                        ranked
                                ? snippets[i]
                                : Entities.escape(plainSnippet(
                                        document.get(FIELD_TEXT))),
                        document.get(FIELD_PLATFORM),
                        document.get(FIELD_IDENTITY),
                        document.get(FIELD_SOURCE_TARGET),
                        document.get(FIELD_WARC_RECORD_ID),
                        post));
            }
            return new SearchResults(totalHits, List.copyOf(hits));
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid search query: " + e.getMessage(), e);
        }
    }

    private void build(
            Path warc, Path index, ProgressReporter progress) throws IOException {
        try (var directory = new NIOFSDirectory(index);
             Analyzer analyzer = new StandardAnalyzer();
             var writer = new IndexWriter(
                     directory,
                     new IndexWriterConfig(analyzer)
                             .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                             .setSimilarity(new BM25Similarity()))) {
            long[] count = {0};
            scanner.scan(warc, scanned -> {
                writer.updateDocument(
                        new Term(FIELD_ID, documentId(scanned)),
                        toDocument(scanned));
                count[0]++;
                if (count[0] % 100 == 0) {
                    progress.report("Indexed " + count[0] + " posts...");
                }
            });
            writer.setLiveCommitData(indexMetadata(warc).entrySet());
            writer.commit();
            progress.report("Indexed " + count[0] + " posts");
        }
    }

    private static Document toDocument(ScannedPost scanned) throws IOException {
        Post post = scanned.post();
        var document = new Document();
        String author = authorText(post);
        String mentions = post.mentions() == null
                ? ""
                : String.join(" ", post.mentions());
        String alt = attachmentText(post);
        String text = searchableText(post);

        document.add(new StringField(
                FIELD_ID, documentId(scanned), Field.Store.NO));
        document.add(new StringField(
                FIELD_PLATFORM, normalize(scanned.platform()), Field.Store.YES));
        document.add(new TextField(FIELD_AUTHOR, author, Field.Store.NO));
        for (String authorIdentity : authorIdentities(post, scanned.platform())) {
            document.add(new StringField(
                    FIELD_AUTHOR_EXACT, authorIdentity, Field.Store.NO));
        }
        document.add(new TextField(FIELD_MENTIONS, mentions, Field.Store.NO));
        document.add(new TextField(FIELD_ALT, alt, Field.Store.NO));
        if (post.url() != null) {
            document.add(new StringField(FIELD_URL, post.url(), Field.Store.YES));
        }
        document.add(new Field(FIELD_TEXT, text, HIGHLIGHTED_TEXT));
        if (post.createdAt() != null) {
            long created = post.createdAt().toEpochMilli();
            document.add(new LongPoint(FIELD_CREATED, created));
            document.add(new NumericDocValuesField(FIELD_CREATED, created));
            document.add(new StoredField(FIELD_CREATED, created));
        }
        document.add(new StringField(
                FIELD_HAS_MEDIA,
                Boolean.toString(hasMedia(post)),
                Field.Store.NO));
        document.add(new StoredField(FIELD_IDENTITY, scanned.identity()));
        document.add(new StoredField(FIELD_SOURCE_TARGET, scanned.sourceTarget()));
        document.add(new StoredField(FIELD_WARC_RECORD_ID, scanned.warcRecordId()));
        document.add(new StoredField(
                FIELD_POST_JSON,
                SocialJson.mapper.writeValueAsString(post)));
        return document;
    }

    private static Query buildQuery(
            SearchOptions options, Analyzer analyzer) throws ParseException {
        var parser = new QueryParser(FIELD_TEXT, analyzer);
        parser.setDefaultOperator(QueryParser.Operator.AND);
        Query textQuery = hasQuery(options)
                ? parser.parse(options.query())
                : new MatchAllDocsQuery();
        var query = new BooleanQuery.Builder()
                .add(textQuery, BooleanClause.Occur.MUST);
        if (options.platform() != null) {
            query.add(new TermQuery(new Term(
                    FIELD_PLATFORM, normalize(options.platform()))),
                    BooleanClause.Occur.FILTER);
        }
        if (options.author() != null) {
            query.add(new TermQuery(new Term(
                    FIELD_AUTHOR_EXACT,
                    normalize(options.author().replaceFirst("^@", "")))),
                    BooleanClause.Occur.FILTER);
        }
        if (options.since() != null || options.until() != null) {
            long lower = options.since() == null
                    ? Long.MIN_VALUE
                    : options.since().toEpochMilli();
            long upper = options.until() == null
                    ? Long.MAX_VALUE
                    : options.until().toEpochMilli();
            query.add(
                    LongPoint.newRangeQuery(FIELD_CREATED, lower, upper),
                    BooleanClause.Occur.FILTER);
        }
        if (options.hasMedia()) {
            query.add(
                    new TermQuery(new Term(FIELD_HAS_MEDIA, "true")),
                    BooleanClause.Occur.FILTER);
        }
        return query.build();
    }

    private static boolean hasQuery(SearchOptions options) {
        return options.query() != null && !options.query().isBlank();
    }

    private static boolean isCurrent(Path warc, Path index) {
        if (!Files.isDirectory(index)) {
            return false;
        }
        try (var directory = new NIOFSDirectory(index)) {
            if (!DirectoryReader.indexExists(directory)) {
                return false;
            }
            try (var reader = DirectoryReader.open(directory)) {
                return reader.getIndexCommit().getUserData()
                        .equals(indexMetadata(warc));
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Map<String, String> indexMetadata(Path warc) throws IOException {
        Path source = warc.toAbsolutePath().normalize();
        FileTime modified = Files.getLastModifiedTime(source);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(META_SCHEMA, SCHEMA_VERSION);
        metadata.put(META_SOURCE, source.toString());
        metadata.put(META_SIZE, Long.toString(Files.size(source)));
        metadata.put(META_MODIFIED, Long.toString(modified.toMillis()));
        return metadata;
    }

    private static String documentId(ScannedPost post) {
        return post.platform() + ":" + post.identity();
    }

    private static String searchableText(Post post) {
        var text = new StringBuilder();
        append(text, plainText(post.content()));
        append(text, authorText(post));
        if (post.mentions() != null) {
            post.mentions().forEach(value -> append(text, value));
        }
        append(text, attachmentText(post));
        if (post.repost() != null) {
            append(text, searchableText(post.repost()));
        }
        if (post.quotedPost() != null) {
            append(text, searchableText(post.quotedPost()));
        }
        return text.toString();
    }

    private static String authorText(Post post) {
        if (post.author() == null) {
            return "";
        }
        return String.join(" ",
                nullToEmpty(post.author().username()),
                nullToEmpty(post.author().displayName())).trim();
    }

    private static List<String> authorIdentities(Post post, String platform) {
        if (post.author() == null ||
                post.author().username() == null ||
                post.author().username().isBlank()) {
            return List.of();
        }
        String username = normalize(post.author().username());
        if ("mastodon".equalsIgnoreCase(platform) &&
                post.site() != null &&
                post.site().name() != null &&
                !post.site().name().isBlank() &&
                !username.contains("@")) {
            return List.of(username, username + "@" + normalize(post.site().name()));
        }
        return List.of(username);
    }

    private static String attachmentText(Post post) {
        var text = new StringBuilder();
        if (post.attachments() != null) {
            for (Attachment attachment : post.attachments()) {
                append(text, attachment.altText());
            }
        }
        return text.toString();
    }

    private static boolean hasMedia(Post post) {
        return post.attachments() != null && !post.attachments().isEmpty();
    }

    private static String plainText(String content) {
        return content == null ? "" : Jsoup.parseBodyFragment(content).text();
    }

    private static String plainSnippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240
                ? normalized
                : normalized.substring(0, 237) + "...";
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(value);
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
