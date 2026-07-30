package org.netpreserve.warcana;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.jsoup.nodes.Entities;
import org.netpreserve.warcana.bluesky.AtProtocolClient;
import org.netpreserve.warcana.bluesky.BlueskyAccountArchiver;
import org.netpreserve.warcana.bluesky.BlueskyAccountReference;
import org.netpreserve.warcana.bluesky.BlueskyWarcResponseExtractor;
import org.netpreserve.warcana.common.MediaCapturer;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.WarcJsonExtractor;
import org.netpreserve.warcana.common.WarcResponseExtractor;
import org.netpreserve.warcana.common.search.SearchHit;
import org.netpreserve.warcana.common.search.SearchOptions;
import org.netpreserve.warcana.common.search.SearchResults;
import org.netpreserve.warcana.common.search.WarcSearchIndex;
import org.netpreserve.warcana.mastodon.MastodonAccountArchiver;
import org.netpreserve.warcana.mastodon.MastodonAccountReference;
import org.netpreserve.warcana.mastodon.MastodonWarcResponseExtractor;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Warcana {
    private static final String ARCHIVE_COMMAND = "archive";
    private static final String EXTRACT_COMMAND = "extract";
    private static final String SEARCH_COMMAND = "search";
    private static final String DEFAULT_USER_AGENT = "warcana/0.1.0";
    private static final long DEFAULT_MAX_MEDIA_BYTES = 100L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_MEDIA_BYTES = 1024L * 1024 * 1024;

    private enum Platform {
        MASTODON,
        BLUESKY;

        static Platform parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown platform '" + value + "' (expected mastodon or bluesky)");
            }
        }
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        if ("-h".equals(args[0]) || "--help".equals(args[0])) {
            usage(out);
            return 0;
        }
        return switch (args[0]) {
            case ARCHIVE_COMMAND -> runArchive(args, out, err);
            case EXTRACT_COMMAND -> runExtract(args, out, err);
            case SEARCH_COMMAND -> runSearch(args, out, err);
            default -> {
                err.println("Unknown command: " + args[0]);
                usage(err);
                yield 2;
            }
        };
    }

    private static int runArchive(String[] args, PrintStream out, PrintStream err) {
        Path output = null;
        String userAgent = DEFAULT_USER_AGENT;
        String service = null;
        String accountArgument = null;
        Integer pageSize = null;
        Platform requestedPlatform = null;
        boolean force = false;
        boolean quiet = false;
        boolean includeMedia = false;
        boolean allVideoVariants = false;
        boolean excludeVideo = false;
        long maxMediaBytes = DEFAULT_MAX_MEDIA_BYTES;
        long maxTotalMediaBytes = DEFAULT_MAX_TOTAL_MEDIA_BYTES;

        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        archiveUsage(out);
                        return 0;
                    }
                    case "-p", "--platform" ->
                            requestedPlatform = Platform.parse(requireValue(args, ++i, args[i - 1]));
                    case "-o", "--output" -> output = Path.of(requireValue(args, ++i, args[i - 1]));
                    case "-A", "--user-agent" -> userAgent = requireValue(args, ++i, args[i - 1]);
                    case "--service" -> service = requireValue(args, ++i, args[i - 1]);
                    case "--page-size" ->
                            pageSize = Integer.parseInt(requireValue(args, ++i, args[i - 1]));
                    case "--include-media" -> includeMedia = true;
                    case "--all-video-variants" -> {
                        includeMedia = true;
                        allVideoVariants = true;
                    }
                    case "--exclude-video" -> {
                        includeMedia = true;
                        excludeVideo = true;
                    }
                    case "--max-media-bytes" ->
                            maxMediaBytes = parseByteSize(requireValue(args, ++i, args[i - 1]));
                    case "--max-total-media-bytes" ->
                            maxTotalMediaBytes = parseByteSize(requireValue(args, ++i, args[i - 1]));
                    case "-f", "--force" -> force = true;
                    case "-q", "--quiet" -> quiet = true;
                    default -> {
                        if (args[i].startsWith("-") && !args[i].startsWith("@")) {
                            throw new IllegalArgumentException("Unknown option: " + args[i]);
                        }
                        if (accountArgument != null) {
                            throw new IllegalArgumentException(
                                    "Only one account may be archived at a time");
                        }
                        accountArgument = args[i];
                    }
                }
            }

            if (accountArgument == null) {
                throw new IllegalArgumentException("Missing account");
            }
            if (excludeVideo && allVideoVariants) {
                throw new IllegalArgumentException(
                        "--exclude-video and --all-video-variants cannot be used together");
            }
            Platform platform = requestedPlatform != null
                    ? requestedPlatform
                    : detectPlatform(accountArgument);
            ProgressReporter progress = quiet
                    ? ProgressReporter.NONE
                    : err::println;

            if (platform == Platform.MASTODON) {
                if (service != null) {
                    throw new IllegalArgumentException("--service is only valid for Bluesky");
                }
                int effectivePageSize = pageSize == null ? 40 : pageSize;
                MastodonAccountReference reference = MastodonAccountReference.parse(accountArgument);
                output = chooseOutput(output, reference.defaultFilename(), force);
                var result = archiveMastodon(
                        reference, output, userAgent, effectivePageSize,
                        includeMedia, !excludeVideo, allVideoVariants,
                        maxMediaBytes, maxTotalMediaBytes, progress);
                out.printf("Archived %d statuses from %s to %s%n",
                        result.statusesArchived(), result.account().acct(), output.toAbsolutePath());
                printMediaSummary(out, includeMedia, result.media());
            } else {
                int effectivePageSize = pageSize == null ? 100 : pageSize;
                String effectiveService = service == null
                        ? AtProtocolClient.PUBLIC_APPVIEW
                        : service;
                BlueskyAccountReference reference = BlueskyAccountReference.parse(accountArgument);
                output = chooseOutput(output, reference.defaultFilename(), force);
                var result = archiveBluesky(
                        reference, output, effectiveService, userAgent, effectivePageSize,
                        includeMedia, !excludeVideo, allVideoVariants,
                        maxMediaBytes, maxTotalMediaBytes, progress);
                out.printf("Archived %d feed items from %s to %s%n",
                        result.feedItemsArchived(), result.profile().handle(), output.toAbsolutePath());
                printMediaSummary(out, includeMedia, result.media());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("archive failed: " + e.getMessage());
            return 1;
        }
    }

    private static Platform detectPlatform(String account) {
        if (account.startsWith("http://") || account.startsWith("https://")) {
            URI uri = URI.create(account);
            if (uri.getHost() != null &&
                    (uri.getHost().equalsIgnoreCase("bsky.app") ||
                            uri.getHost().equalsIgnoreCase("www.bsky.app")) &&
                    uri.getPath().startsWith("/profile/")) {
                return Platform.BLUESKY;
            }
            return Platform.MASTODON;
        }
        if (account.startsWith("did:")) {
            return Platform.BLUESKY;
        }
        String withoutLeadingAt = account.replaceFirst("^@", "");
        if (withoutLeadingAt.contains("@")) {
            return Platform.MASTODON;
        }
        throw new IllegalArgumentException(
                "Cannot determine platform from account handle; use --platform mastodon or --platform bluesky");
    }

    private static Path chooseOutput(Path requested, String defaultFilename,
                                     boolean force) {
        Path output = requested == null ? Path.of(defaultFilename) : requested;
        if (Files.exists(output) && !force) {
            throw new IllegalArgumentException(
                    "Output already exists: " + output + " (use --force to replace it)");
        }
        return output;
    }

    private static int runExtract(String[] args, PrintStream out, PrintStream err) {
        Path input = null;
        Path output = null;
        boolean force = false;
        boolean pretty = false;

        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        extractUsage(out);
                        return 0;
                    }
                    case "-o", "--output" -> output = Path.of(requireValue(args, ++i, args[i - 1]));
                    case "-f", "--force" -> force = true;
                    case "--pretty" -> pretty = true;
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + args[i]);
                        }
                        if (input != null) {
                            throw new IllegalArgumentException(
                                    "Only one WARC file may be read at a time");
                        }
                        input = Path.of(args[i]);
                    }
                }
            }

            if (input == null) {
                throw new IllegalArgumentException("Missing WARC file");
            }
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("WARC file does not exist: " + input);
            }
            if (output != null && Files.exists(output) && !force) {
                throw new IllegalArgumentException(
                        "Output already exists: " + output + " (use --force to replace it)");
            }

            var results = new WarcJsonExtractor(responseExtractors()).extract(input);
            byte[] json = pretty
                    ? SocialJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(results)
                    : SocialJson.mapper.writeValueAsBytes(results);
            if (output == null) {
                out.write(json);
                out.println();
            } else {
                Files.write(output, json);
                out.printf("Extracted %d posts to %s%n",
                        results.totalHits(), output.toAbsolutePath());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("extraction failed: " + e.getMessage());
            return 1;
        }
    }

    private static int runSearch(String[] args, PrintStream out, PrintStream err) {
        Path input = null;
        Path index = null;
        String platform = null;
        String author = null;
        Instant since = null;
        Instant until = null;
        boolean hasMedia = false;
        boolean reindex = false;
        boolean quiet = false;
        int limit = 20;
        int offset = 0;
        SearchFormat format = SearchFormat.TEXT;
        ColorMode colorMode = ColorMode.AUTO;
        var queryParts = new ArrayList<String>();

        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        searchUsage(out);
                        return 0;
                    }
                    case "--index" -> index = Path.of(requireValue(args, ++i, args[i - 1]));
                    case "--reindex" -> reindex = true;
                    case "--platform" -> platform = Platform
                            .parse(requireValue(args, ++i, args[i - 1]))
                            .name().toLowerCase(Locale.ROOT);
                    case "--author" -> author = requireValue(args, ++i, args[i - 1]);
                    case "--since" ->
                            since = parseSearchDate(requireValue(args, ++i, args[i - 1]), false);
                    case "--until" ->
                            until = parseSearchDate(requireValue(args, ++i, args[i - 1]), true);
                    case "--has-media" -> hasMedia = true;
                    case "--limit" ->
                            limit = Integer.parseInt(requireValue(args, ++i, args[i - 1]));
                    case "--offset" ->
                            offset = Integer.parseInt(requireValue(args, ++i, args[i - 1]));
                    case "--format" ->
                            format = SearchFormat.parse(requireValue(args, ++i, args[i - 1]));
                    case "--color" ->
                            colorMode = ColorMode.parse(requireValue(args, ++i, args[i - 1]));
                    case "-q", "--quiet" -> quiet = true;
                    case "--" -> {
                        for (i++; i < args.length; i++) {
                            queryParts.add(args[i]);
                        }
                    }
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + args[i]);
                        }
                        if (input == null) {
                            input = Path.of(args[i]);
                        } else {
                            queryParts.add(args[i]);
                        }
                    }
                }
            }

            if (input == null) {
                throw new IllegalArgumentException("Missing WARC file");
            }
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("WARC file does not exist: " + input);
            }
            String query = String.join(" ", queryParts);
            if (limit < 1 || limit > 1000) {
                throw new IllegalArgumentException("--limit must be between 1 and 1000");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("--offset must not be negative");
            }
            if (since != null && until != null && since.isAfter(until)) {
                throw new IllegalArgumentException("--since must not be after --until");
            }
            if (index == null) {
                index = Path.of(input.toString() + ".index");
            }

            ProgressReporter progress = quiet
                    ? ProgressReporter.NONE
                    : err::println;
            var searchIndex = new WarcSearchIndex(responseExtractors());
            searchIndex.ensureCurrent(input, index, reindex, progress);
            SearchResults results = searchIndex.search(
                    index,
                    new SearchOptions(
                            query,
                            platform,
                            author,
                            since,
                            until,
                            hasMedia,
                            limit,
                            offset));
            writeSearchResults(
                    results,
                    format,
                    out,
                    colorMode.enabled(out));
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("search failed: " + e.getMessage());
            return 1;
        }
    }

    private static List<WarcResponseExtractor> responseExtractors() {
        return List.of(
                new MastodonWarcResponseExtractor(),
                new BlueskyWarcResponseExtractor());
    }

    private static Instant parseSearchDate(String value, boolean endOfDay) {
        try {
            if (value.contains("T")) {
                return Instant.parse(value);
            }
            LocalDate date = LocalDate.parse(value);
            return endOfDay
                    ? date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
                    : date.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid date '" + value + "' (expected YYYY-MM-DD or an ISO-8601 instant)");
        }
    }

    private static void writeSearchResults(
            SearchResults results, SearchFormat format,
            PrintStream out, boolean color) throws IOException {
        if (format == SearchFormat.JSON) {
            out.write(SocialJson.mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(results));
            out.println();
            return;
        }
        if (format == SearchFormat.JSONL) {
            for (SearchHit hit : results.hits()) {
                out.println(SocialJson.mapper.writeValueAsString(hit));
            }
            return;
        }

        String heading = results.totalHits() + " result" +
                (results.totalHits() == 1 ? "" : "s");
        out.printf("%s%n", color ? Ansi.BOLD + heading + Ansi.RESET : heading);
        for (SearchHit hit : results.hits()) {
            var post = hit.post();
            String author = post.author() == null
                    ? ""
                    : nullToEmpty(post.author().username());
            out.println();
            String date = post.createdAt() == null
                    ? "unknown date"
                    : post.createdAt().toString();
            String url = post.url();
            if (color) {
                out.printf("%s@%s%s · %s%s%s · %s%s%s",
                        Ansi.BOLD_CYAN, author, Ansi.RESET,
                        Ansi.MAGENTA, hit.platform(), Ansi.RESET,
                        Ansi.DIM, date, Ansi.RESET);
                if (hit.score() != null) {
                    out.printf(" · %sscore %.2f%s",
                            Ansi.YELLOW, hit.score(), Ansi.RESET);
                }
                if (url != null) {
                    out.printf(" · %s%s%s", Ansi.BLUE_UNDERLINE, url, Ansi.RESET);
                }
                out.println();
            } else {
                out.printf("@%s · %s · %s",
                        author, hit.platform(), date);
                if (hit.score() != null) {
                    out.printf(" · score %.2f", hit.score());
                }
                if (url != null) {
                    out.print(" · " + url);
                }
                out.println();
            }
            String snippet = color
                    ? hit.snippet()
                            .replace("<b>", Ansi.BOLD_YELLOW)
                            .replace("</b>", Ansi.RESET)
                    : hit.snippet()
                            .replace("<b>", "[")
                            .replace("</b>", "]");
            out.println(Entities.unescape(snippet));
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum SearchFormat {
        TEXT,
        JSON,
        JSONL;

        static SearchFormat parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown search format '" + value +
                                "' (expected text, json or jsonl)");
            }
        }
    }

    private enum ColorMode {
        AUTO,
        ALWAYS,
        NEVER;

        static ColorMode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown color mode '" + value +
                                "' (expected auto, always or never)");
            }
        }

        boolean enabled(PrintStream out) {
            return switch (this) {
                case ALWAYS -> true;
                case NEVER -> false;
                case AUTO -> out == System.out &&
                        System.console() != null &&
                        System.getenv("NO_COLOR") == null &&
                        !"dumb".equalsIgnoreCase(System.getenv("TERM"));
            };
        }
    }

    private static class Ansi {
        private static final String RESET = "\u001b[0m";
        private static final String BOLD = "\u001b[1m";
        private static final String DIM = "\u001b[2m";
        private static final String YELLOW = "\u001b[33m";
        private static final String MAGENTA = "\u001b[35m";
        private static final String BOLD_CYAN = "\u001b[1;36m";
        private static final String BOLD_YELLOW = "\u001b[1;33m";
        private static final String BLUE_UNDERLINE = "\u001b[4;34m";

        private Ansi() {
        }
    }

    private static MastodonAccountArchiver.ArchiveResult archiveMastodon(
            MastodonAccountReference reference, Path output,
            String userAgent, int pageSize, boolean includeMedia,
            boolean includeVideo, boolean allVideoVariants,
            long maxMediaBytes, long maxTotalMediaBytes,
            ProgressReporter progress) throws IOException {
        String accountAddress = reference.account() + "@" +
                URI.create(reference.server()).getRawAuthority();
        Map<String, List<String>> metadata = archiveMetadata(
                userAgent, "mastodon", accountAddress, includeMedia,
                includeVideo, allVideoVariants, maxMediaBytes, maxTotalMediaBytes);

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(output.getFileName().toString())
                    .fields(metadata)
                    .build());
            MediaSink mediaSink = includeMedia
                    ? new MediaCapturer(
                            warcWriter, userAgent, maxMediaBytes, maxTotalMediaBytes,
                            allVideoVariants, progress)
                    : null;
            return new MastodonAccountArchiver(
                    reference.server(), userAgent, warcWriter, pageSize,
                    mediaSink, progress, includeVideo)
                    .archive(reference.account());
        }
    }

    private static BlueskyAccountArchiver.ArchiveResult archiveBluesky(
            BlueskyAccountReference reference, Path output, String service,
            String userAgent, int pageSize, boolean includeMedia,
            boolean includeVideo, boolean allVideoVariants,
            long maxMediaBytes, long maxTotalMediaBytes,
            ProgressReporter progress) throws IOException {
        Map<String, List<String>> metadata = archiveMetadata(
                userAgent, "bluesky", reference.actor(), includeMedia,
                includeVideo, allVideoVariants, maxMediaBytes, maxTotalMediaBytes);
        metadata.put("api", List.of(service));

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(output.getFileName().toString())
                    .fields(metadata)
                    .build());
            MediaSink mediaSink = includeMedia
                    ? new MediaCapturer(
                            warcWriter, userAgent, maxMediaBytes, maxTotalMediaBytes,
                            allVideoVariants, progress)
                    : null;
            return new BlueskyAccountArchiver(
                    service, userAgent, warcWriter, pageSize,
                    mediaSink, progress, includeVideo)
                    .archive(reference.actor());
        }
    }

    private static Map<String, List<String>> archiveMetadata(
            String userAgent, String platform, String account,
            boolean includeMedia, boolean includeVideo, boolean allVideoVariants,
            long maxMediaBytes, long maxTotalMediaBytes) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("software", List.of(userAgent));
        metadata.put("format", List.of("WARC File Format 1.1"));
        metadata.put("platform", List.of(platform));
        metadata.put("account", List.of(account));
        metadata.put("media", List.of(Boolean.toString(includeMedia)));
        if (includeMedia) {
            metadata.put("max-media-bytes", List.of(Long.toString(maxMediaBytes)));
            metadata.put("max-total-media-bytes", List.of(Long.toString(maxTotalMediaBytes)));
            metadata.put("video", List.of(includeVideo ? "included" : "excluded"));
            if (includeVideo) {
                metadata.put("video-variants",
                        List.of(allVideoVariants ? "all" : "highest-bandwidth"));
            }
        }
        metadata.put("created", List.of(Instant.now().toString()));
        return metadata;
    }

    private static void printMediaSummary(
            PrintStream out, boolean includeMedia, MediaCaptureStats stats) {
        if (includeMedia) {
            out.printf("Captured %d media resources (%d bytes); %d failed, %d skipped%n",
                    stats.captured(), stats.bytes(), stats.failed(), stats.skipped());
        }
    }

    static long parseByteSize(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        long multiplier = 1;
        String number = normalized;
        String[] suffixes = {"KIB", "MIB", "GIB", "KB", "MB", "GB", "K", "M", "G"};
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix)) {
                number = normalized.substring(0, normalized.length() - suffix.length()).trim();
                multiplier = switch (suffix.charAt(0)) {
                    case 'K' -> 1024L;
                    case 'M' -> 1024L * 1024;
                    case 'G' -> 1024L * 1024 * 1024;
                    default -> 1L;
                };
                break;
            }
        }
        try {
            long bytes = Math.multiplyExact(Long.parseLong(number), multiplier);
            if (bytes < 1) {
                throw new NumberFormatException();
            }
            return bytes;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid byte size: " + value);
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: warcana <command> [options]");
        stream.println();
        stream.println("Commands:");
        stream.printf("  %-12s %s%n", ARCHIVE_COMMAND,
                "Archive a Mastodon or Bluesky account to WARC");
        stream.printf("  %-12s %s%n", EXTRACT_COMMAND,
                "Extract common-model JSON from a WARC");
        stream.printf("  %-12s %s%n", SEARCH_COMMAND,
                "Full-text search posts in a WARC");
        stream.println();
        stream.println("Run 'warcana <command> --help' for command options.");
    }

    private static void archiveUsage(PrintStream stream) {
        stream.println("Usage: warcana " + ARCHIVE_COMMAND + " [options] ACCOUNT");
        stream.println();
        stream.println("Archives a Mastodon or Bluesky account.");
        stream.println("Use --platform when an account handle is ambiguous.");
        stream.println();
        stream.println("Options:");
        stream.println("  -p, --platform NAME    mastodon or bluesky");
        stream.println("  -o, --output FILE      Output path");
        stream.println("  -A, --user-agent TEXT  HTTP User-Agent (default: " +
                DEFAULT_USER_AGENT + ")");
        stream.println("  --page-size NUMBER     Items per API request");
        stream.println("  --service URL          Bluesky AT Protocol AppView");
        stream.println("  --include-media        Capture linked media into the WARC");
        stream.println("  --all-video-variants   Capture every HLS variant instead of only");
        stream.println("                         the highest-bandwidth variant");
        stream.println("  --exclude-video        Capture other media and video previews,");
        stream.println("                         but omit video content");
        stream.println("  --max-media-bytes N    Per-resource limit (default: 100M)");
        stream.println("  --max-total-media-bytes N");
        stream.println("                         Total media limit (default: 1G)");
        stream.println("  -f, --force            Replace an existing output file");
        stream.println("  -q, --quiet            Suppress progress output");
        stream.println("  -h, --help             Show this help");
    }

    private static void extractUsage(PrintStream stream) {
        stream.println("Usage: warcana " + EXTRACT_COMMAND + " [options] WARC_FILE");
        stream.println();
        stream.println("Extracts Mastodon and Bluesky" +
                " posts as common-model SocialResults JSON.");
        stream.println("Writes JSON to stdout unless --output is specified.");
        stream.println();
        stream.println("Options:");
        stream.println("  -o, --output FILE  Write JSON to FILE instead of stdout");
        stream.println("  --pretty           Pretty-print the JSON");
        stream.println("  -f, --force        Replace an existing output file");
        stream.println("  -h, --help         Show this help");
    }

    private static void searchUsage(PrintStream stream) {
        stream.println("Usage: warcana " + SEARCH_COMMAND +
                " [options] WARC_FILE [QUERY]");
        stream.println();
        stream.println("Full-text searches Mastodon and Bluesky posts.");
        stream.println("With no query, lists the most recent posts first.");
        stream.println("The first search builds a reusable WARC_FILE.index sidecar.");
        stream.println("Terms are ANDed by default; quotes, OR, NOT and field queries");
        stream.println("(text:, author:, mentions:, alt:, platform:) are supported.");
        stream.println();
        stream.println("Options:");
        stream.println("  --index DIRECTORY  Use a custom index directory");
        stream.println("  --reindex          Rebuild the search index");
        stream.println("  --platform NAME    Filter by mastodon or bluesky");
        stream.println("  --author HANDLE    Filter by exact author handle");
        stream.println("  --since DATE       Include posts on or after DATE");
        stream.println("  --until DATE       Include posts on or before DATE");
        stream.println("  --has-media        Only include posts with attachments");
        stream.println("  --limit NUMBER     Maximum results (default: 20)");
        stream.println("  --offset NUMBER    Skip the first NUMBER results");
        stream.println("  --format FORMAT    text, json or jsonl (default: text)");
        stream.println("  --color WHEN       auto, always or never (default: auto)");
        stream.println("  -q, --quiet        Suppress index progress output");
        stream.println("  -h, --help         Show this help");
    }
}
