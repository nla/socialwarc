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
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
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
import org.netpreserve.warcana.common.source.LocalWarcSource;
import org.netpreserve.warcana.common.source.WarcSource;
import org.netpreserve.warcana.common.model.Attachment;
import org.netpreserve.warcana.common.model.Post;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String SCHEMA_VERSION = "11";
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
    private static final String FIELD_SOURCE_WARC = "source_warc";
    private static final String FIELD_WARC_OFFSET = "warc_offset";
    private static final String FIELD_WARC_DATE = "warc_date";
    private static final String FIELD_NATIVE_ID = "native_id";
    private static final String FIELD_SITE = "site";
    private static final String FIELD_REPLY_PARENT = "reply_parent";
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
        return ensureCurrent(List.of(warc), index, rebuild, progress);
    }

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
        if (warcs.isEmpty()) {
            throw new IOException("Collection contains no completed WARC files");
        }
        if (!rebuild && isCurrent(warcs, index)) {
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

            boolean hasQuery = hasQuery(options);
            SearchSort sort = options.sort() == SearchSort.AUTO
                    ? hasQuery ? SearchSort.RELEVANCE : SearchSort.NEWEST
                    : options.sort();
            if (!hasQuery && sort == SearchSort.RELEVANCE) {
                sort = SearchSort.NEWEST;
            }
            boolean ranked = sort == SearchSort.RELEVANCE;
            TopDocs topDocs;
            if (ranked) {
                topDocs = searcher.search(query, requested);
            } else {
                var created = new SortField(
                        FIELD_CREATED,
                        SortField.Type.LONG,
                        sort == SearchSort.NEWEST);
                created.setMissingValue(sort == SearchSort.NEWEST
                        ? Long.MIN_VALUE
                        : Long.MAX_VALUE);
                topDocs = searcher.search(
                        query,
                        requested,
                        new Sort(created, SortField.FIELD_DOC));
            }
            String[] snippets = hasQuery
                    ? UnifiedHighlighter
                    .builder(searcher, analyzer)
                    .withFormatter(new DefaultPassageFormatter(
                            "<b>", "</b>", " … ", true))
                    .build()
                    .highlight(FIELD_TEXT, query, topDocs, 1)
                    : null;
            var storedFields = searcher.storedFields();
            var hits = new ArrayList<SearchHit>();
            for (int i = options.offset(); i < topDocs.scoreDocs.length; i++) {
                ScoreDoc scoreDoc = topDocs.scoreDocs[i];
                Document document = storedFields.document(scoreDoc.doc);
                hits.add(toHit(
                        document,
                        ranked ? scoreDoc.score : null,
                        hasQuery
                                ? snippets[i]
                                : Entities.escape(plainSnippet(
                                        document.get(FIELD_TEXT)))));
            }
            return new SearchResults(totalHits, List.copyOf(hits));
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid search query: " + e.getMessage(), e);
        }
    }

    public SearchTimeline timeline(Path index, SearchOptions options)
            throws IOException {
        try (var directory = new NIOFSDirectory(index);
             var reader = DirectoryReader.open(directory);
             Analyzer analyzer = new StandardAnalyzer()) {
            var searcher = new IndexSearcher(reader);
            var collector = new CreatedDateCollector();
            searcher.search(buildQuery(options, analyzer, false), collector);
            return buildTimeline(collector.values());
        } catch (ParseException e) {
            throw new IllegalArgumentException(
                    "Invalid search query: " + e.getMessage(), e);
        }
    }

    public SearchHit find(Path index, String platform, String identity)
            throws IOException {
        try (var directory = new NIOFSDirectory(index);
             var reader = DirectoryReader.open(directory)) {
            var searcher = new IndexSearcher(reader);
            TopDocs results = searcher.search(
                    new TermQuery(new Term(
                            FIELD_ID, platform + ":" + identity)),
                    1);
            if (results.scoreDocs.length == 0) {
                return null;
            }
            Document document = searcher.storedFields()
                    .document(results.scoreDocs[0].doc);
            return toHit(
                    document,
                    null,
                    Entities.escape(plainSnippet(document.get(FIELD_TEXT))));
        }
    }

    public SearchHit findByNativeId(
            Path index, String platform, String site, String nativeId)
            throws IOException {
        if (platform == null || site == null || nativeId == null) {
            return null;
        }
        try (var directory = new NIOFSDirectory(index);
             var reader = DirectoryReader.open(directory)) {
            var searcher = new IndexSearcher(reader);
            return findByNativeId(searcher, platform, site, nativeId);
        }
    }

    public List<SearchHit> thread(Path index, SearchHit selected)
            throws IOException {
        Post selectedPost = displayedPost(selected.post());
        if (selectedPost.id() == null ||
                selectedPost.site() == null ||
                selectedPost.site().name() == null) {
            return List.of(selected);
        }
        String platform = selected.platform();
        String site = selectedPost.site().name();
        var hits = new LinkedHashMap<String, SearchHit>();
        var pending = new ArrayDeque<SearchHit>();
        hits.put(selectedPost.id(), selected);
        pending.add(selected);

        try (var directory = new NIOFSDirectory(index);
             var reader = DirectoryReader.open(directory)) {
            var searcher = new IndexSearcher(reader);
            while (!pending.isEmpty()) {
                SearchHit hit = pending.removeFirst();
                Post post = displayedPost(hit.post());
                if (post.thread() != null) {
                    addThreadReference(
                            searcher, platform, site,
                            post.thread().parent(), hits, pending);
                    addThreadReference(
                            searcher, platform, site,
                            post.thread().root(), hits, pending);
                }
                for (SearchHit reply : findReplies(
                        searcher, platform, site, post.id())) {
                    Post replyPost = displayedPost(reply.post());
                    if (replyPost.id() != null &&
                            hits.putIfAbsent(replyPost.id(), reply) == null) {
                        pending.addLast(reply);
                    }
                }
            }
        }

        return hits.values().stream()
                .sorted(Comparator
                        .comparing(
                                (SearchHit hit) -> displayedPost(hit.post())
                                        .createdAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SearchHit::identity))
                .toList();
    }

    private static void addThreadReference(
            IndexSearcher searcher,
            String platform,
            String site,
            Post.PostReference reference,
            Map<String, SearchHit> hits,
            ArrayDeque<SearchHit> pending) throws IOException {
        if (reference == null || reference.id() == null ||
                hits.containsKey(reference.id())) {
            return;
        }
        SearchHit hit = findByNativeId(
                searcher, platform, site, reference.id());
        if (hit != null) {
            hits.put(reference.id(), hit);
            pending.addLast(hit);
        }
    }

    private static SearchHit findByNativeId(
            IndexSearcher searcher,
            String platform,
            String site,
            String nativeId) throws IOException {
        TopDocs results = searcher.search(scopedNativeQuery(
                platform, site, FIELD_NATIVE_ID, nativeId), 1);
        return results.scoreDocs.length == 0
                ? null
                : toHit(
                        searcher.storedFields().document(
                                results.scoreDocs[0].doc),
                        null,
                        "");
    }

    private static List<SearchHit> findReplies(
            IndexSearcher searcher,
            String platform,
            String site,
            String parentId) throws IOException {
        if (parentId == null) {
            return List.of();
        }
        TopDocs results = searcher.search(
                scopedNativeQuery(
                        platform, site, FIELD_REPLY_PARENT, parentId),
                Math.max(1, searcher.getIndexReader().maxDoc()));
        var replies = new ArrayList<SearchHit>(results.scoreDocs.length);
        var storedFields = searcher.storedFields();
        for (ScoreDoc scoreDoc : results.scoreDocs) {
            replies.add(toHit(
                    storedFields.document(scoreDoc.doc), null, ""));
        }
        return replies;
    }

    private static Query scopedNativeQuery(
            String platform, String site, String field, String value) {
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term(
                                FIELD_PLATFORM, normalize(platform))),
                        BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(
                                FIELD_SITE, normalize(site))),
                        BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(field, value)),
                        BooleanClause.Occur.MUST)
                .build();
    }

    private static SearchHit toHit(
            Document document, Float score, String snippet) throws IOException {
        Post post = SocialJson.mapper.readValue(
                document.get(FIELD_POST_JSON), Post.class);
        return new SearchHit(
                score,
                snippet,
                document.get(FIELD_PLATFORM),
                document.get(FIELD_IDENTITY),
                document.get(FIELD_SOURCE_TARGET),
                document.get(FIELD_WARC_RECORD_ID),
                document.get(FIELD_SOURCE_WARC),
                document.getField(FIELD_WARC_OFFSET)
                        .numericValue().longValue(),
                Instant.ofEpochMilli(document.getField(FIELD_WARC_DATE)
                        .numericValue().longValue()),
                post);
    }

    private void build(
            List<? extends WarcSource> warcs, Path index,
            ProgressReporter progress) throws IOException {
        try (var directory = new NIOFSDirectory(index);
             Analyzer analyzer = new StandardAnalyzer();
             var writer = new IndexWriter(
                     directory,
                     new IndexWriterConfig(analyzer)
                             .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                             .setSimilarity(new BM25Similarity()))) {
            long[] count = {0};
            scanner.scanSources(warcs, scanned -> {
                writer.updateDocument(
                        new Term(FIELD_ID, documentId(scanned)),
                        toDocument(scanned));
                count[0]++;
                if (count[0] % 100 == 0) {
                    progress.report("Indexed " + count[0] + " posts...");
                }
            });
            writer.setLiveCommitData(indexMetadata(warcs).entrySet());
            writer.commit();
            progress.report("Indexed " + count[0] + " posts");
        }
    }

    private static Document toDocument(ScannedPost scanned) throws IOException {
        Post post = scanned.post();
        Post displayed = displayedPost(post);
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
        if (displayed.id() != null &&
                displayed.site() != null &&
                displayed.site().name() != null) {
            document.add(new StringField(
                    FIELD_NATIVE_ID, displayed.id(), Field.Store.NO));
            document.add(new StringField(
                    FIELD_SITE,
                    normalize(displayed.site().name()),
                    Field.Store.NO));
            if (displayed.thread() != null &&
                    displayed.thread().parent() != null &&
                    displayed.thread().parent().id() != null) {
                document.add(new StringField(
                        FIELD_REPLY_PARENT,
                        displayed.thread().parent().id(),
                        Field.Store.NO));
            }
        }
        document.add(new TextField(FIELD_AUTHOR, author, Field.Store.NO));
        if (post.author() != null &&
                post.author().username() != null &&
                !post.author().username().isBlank()) {
            document.add(new StringField(
                    FIELD_AUTHOR_EXACT,
                    normalize(post.author().username()),
                    Field.Store.NO));
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
        document.add(new StoredField(FIELD_SOURCE_WARC, scanned.sourceWarc()));
        document.add(new StoredField(FIELD_WARC_OFFSET, scanned.warcOffset()));
        document.add(new StoredField(
                FIELD_WARC_DATE, scanned.warcDate().toEpochMilli()));
        document.add(new StoredField(
                FIELD_POST_JSON,
                SocialJson.mapper.writeValueAsString(post)));
        return document;
    }

    private static Query buildQuery(
            SearchOptions options, Analyzer analyzer) throws ParseException {
        return buildQuery(options, analyzer, true);
    }

    private static Query buildQuery(
            SearchOptions options, Analyzer analyzer, boolean includeDates)
            throws ParseException {
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
        if (includeDates &&
                (options.since() != null || options.until() != null)) {
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

    private static SearchTimeline buildTimeline(long[] timestamps) {
        if (timestamps.length == 0) {
            return SearchTimeline.empty();
        }
        LocalDate minimum = Instant.ofEpochMilli(Arrays.stream(timestamps)
                        .min().orElseThrow())
                .atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate maximum = Instant.ofEpochMilli(Arrays.stream(timestamps)
                        .max().orElseThrow())
                .atZone(ZoneOffset.UTC).toLocalDate();
        SearchTimeline.Unit unit = timelineUnit(minimum, maximum);
        LocalDate start = bucketStart(minimum, unit);
        LocalDate finalBucket = bucketStart(maximum, unit);
        var counts = new LinkedHashMap<LocalDate, Long>();
        for (LocalDate bucket = start;
             !bucket.isAfter(finalBucket);
             bucket = nextBucket(bucket, unit)) {
            counts.put(bucket, 0L);
        }
        for (long timestamp : timestamps) {
            LocalDate date = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneOffset.UTC).toLocalDate();
            counts.computeIfPresent(
                    bucketStart(date, unit),
                    (ignored, count) -> count + 1);
        }
        List<SearchTimeline.Bucket> buckets = counts.entrySet().stream()
                .map(entry -> new SearchTimeline.Bucket(
                        entry.getKey(),
                        nextBucket(entry.getKey(), unit).minusDays(1),
                        entry.getValue()))
                .toList();
        return new SearchTimeline(
                buckets.getFirst().start(),
                buckets.getLast().end(),
                unit,
                buckets);
    }

    private static SearchTimeline.Unit timelineUnit(
            LocalDate minimum, LocalDate maximum) {
        long days = ChronoUnit.DAYS.between(minimum, maximum) + 1;
        if (days <= 60) {
            return SearchTimeline.Unit.DAY;
        }
        if (days <= 60 * 7) {
            return SearchTimeline.Unit.WEEK;
        }
        long months = ChronoUnit.MONTHS.between(
                minimum.withDayOfMonth(1), maximum.withDayOfMonth(1)) + 1;
        return months <= 60
                ? SearchTimeline.Unit.MONTH
                : SearchTimeline.Unit.YEAR;
    }

    private static LocalDate bucketStart(
            LocalDate date, SearchTimeline.Unit unit) {
        return switch (unit) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(
                    java.time.DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    private static LocalDate nextBucket(
            LocalDate date, SearchTimeline.Unit unit) {
        return switch (unit) {
            case DAY -> date.plusDays(1);
            case WEEK -> date.plusWeeks(1);
            case MONTH -> date.plusMonths(1);
            case YEAR -> date.plusYears(1);
        };
    }

    private static final class CreatedDateCollector extends SimpleCollector {
        private NumericDocValues created;
        private long[] values = new long[64];
        private int size;

        @Override
        protected void doSetNextReader(LeafReaderContext context)
                throws IOException {
            created = DocValues.getNumeric(context.reader(), FIELD_CREATED);
        }

        @Override
        public void collect(int document) throws IOException {
            if (created.advanceExact(document)) {
                if (size == values.length) {
                    values = Arrays.copyOf(values, values.length * 2);
                }
                values[size++] = created.longValue();
            }
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }

        long[] values() {
            return Arrays.copyOf(values, size);
        }
    }

    private static boolean hasQuery(SearchOptions options) {
        return options.query() != null && !options.query().isBlank();
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
                        .equals(indexMetadata(warcs));
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Map<String, String> indexMetadata(
            List<? extends WarcSource> warcs) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(META_SCHEMA, SCHEMA_VERSION);
        var sources = new StringBuilder();
        long size = 0;
        for (WarcSource source : warcs) {
            sources.append(source.id())
                    .append('\t')
                    .append(source.size())
                    .append('\t')
                    .append(source.version())
                    .append('\n');
            size += source.size();
        }
        metadata.put(META_SOURCE, sources.toString());
        metadata.put(META_SIZE, Long.toString(size));
        metadata.put(META_MODIFIED, "source-version");
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

    private static String attachmentText(Post post) {
        var text = new StringBuilder();
        if (post.attachments() != null) {
            for (Attachment attachment : post.attachments()) {
                append(text, attachment.altText());
                append(text, attachment.title());
                append(text, attachment.description());
            }
        }
        return text.toString();
    }

    private static boolean hasMedia(Post post) {
        if (post == null) {
            return false;
        }
        if (post.attachments() != null && post.attachments().stream()
                .anyMatch(attachment -> attachment != null &&
                        !"link".equalsIgnoreCase(attachment.type()))) {
            return true;
        }
        return hasMedia(post.repost()) || hasMedia(post.quotedPost());
    }

    private static Post displayedPost(Post post) {
        return post.repost() == null ? post : post.repost();
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
