package org.netpreserve.warcana;

import org.jsoup.nodes.Entities;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.collection.CollectionStore;
import org.netpreserve.warcana.common.search.SearchHit;
import org.netpreserve.warcana.common.search.SearchOptions;
import org.netpreserve.warcana.common.search.SearchResults;
import org.netpreserve.warcana.common.search.SearchSort;
import org.netpreserve.warcana.common.search.WarcSearchIndex;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SearchCommand {
    static int run(String[] args, PrintStream out, PrintStream err) {
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
        SearchSort sort = SearchSort.AUTO;
        SearchFormat format = SearchFormat.TEXT;
        ColorMode colorMode = ColorMode.AUTO;
        var queryParts = new ArrayList<String>();

        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        usage(out);
                        return 0;
                    }
                    case "--index" -> index = Path.of(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "--reindex" -> reindex = true;
                    case "--platform" -> platform = CliSupport.Platform
                            .parse(CliSupport.requireValue(
                                    args, ++i, args[i - 1]))
                            .name().toLowerCase(Locale.ROOT);
                    case "--author" -> author = CliSupport.requireValue(
                            args, ++i, args[i - 1]);
                    case "--since" -> since = CliSupport.parseDate(
                            CliSupport.requireValue(args, ++i, args[i - 1]),
                            false);
                    case "--until" -> until = CliSupport.parseDate(
                            CliSupport.requireValue(args, ++i, args[i - 1]),
                            true);
                    case "--has-media" -> hasMedia = true;
                    case "--limit" -> limit = Integer.parseInt(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "--offset" -> offset = Integer.parseInt(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "--sort" -> sort = SearchSort.parse(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "--format" -> format = SearchFormat.parse(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "--color" -> colorMode = ColorMode.parse(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "-q", "--quiet" -> quiet = true;
                    case "--" -> {
                        for (i++; i < args.length; i++) {
                            queryParts.add(args[i]);
                        }
                    }
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException(
                                    "Unknown option: " + args[i]);
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
            CollectionStore collectionStore = new CollectionStore(input);
            boolean collectionInput = Files.isDirectory(input) &&
                    collectionStore.isCollection();
            List<Path> warcs;
            if (collectionInput) {
                warcs = collectionStore.completedPostWarcs();
                if (warcs.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Collection contains no completed WARC files: " +
                                    input);
                }
            } else {
                if (!Files.isRegularFile(input)) {
                    throw new IllegalArgumentException(
                            "WARC file or collection does not exist: " + input);
                }
                warcs = List.of(input);
            }
            String query = String.join(" ", queryParts);
            if (limit < 1 || limit > 1000) {
                throw new IllegalArgumentException(
                        "--limit must be between 1 and 1000");
            }
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "--offset must not be negative");
            }
            if (since != null && until != null && since.isAfter(until)) {
                throw new IllegalArgumentException(
                        "--since must not be after --until");
            }
            if (index == null) {
                index = collectionInput
                        ? collectionStore.indexDirectory()
                        : Path.of(input.toString() + ".index");
            }

            ProgressReporter progress = quiet
                    ? ProgressReporter.NONE
                    : err::println;
            var searchIndex = new WarcSearchIndex(
                    CliSupport.responseExtractors());
            searchIndex.ensureCurrent(warcs, index, reindex, progress);
            SearchResults results = searchIndex.search(
                    index,
                    new SearchOptions(
                            query, platform, author, since, until,
                            hasMedia, sort, limit, offset));
            writeResults(results, format, out, colorMode.enabled(out));
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("search failed: " + e.getMessage());
            return 1;
        }
    }

    private static void writeResults(
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
                    ? "" : nullToEmpty(post.author().username());
            out.println();
            String date = post.createdAt() == null
                    ? "unknown date" : post.createdAt().toString();
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
                    out.printf(" · %s%s%s",
                            Ansi.BLUE_UNDERLINE, url, Ansi.RESET);
                }
                out.println();
            } else {
                out.printf("@%s · %s · %s", author, hit.platform(), date);
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
        TEXT, JSON, JSONL;

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
        AUTO, ALWAYS, NEVER;

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
        static final String RESET = "\u001b[0m";
        static final String BOLD = "\u001b[1m";
        static final String DIM = "\u001b[2m";
        static final String YELLOW = "\u001b[33m";
        static final String MAGENTA = "\u001b[35m";
        static final String BOLD_CYAN = "\u001b[1;36m";
        static final String BOLD_YELLOW = "\u001b[1;33m";
        static final String BLUE_UNDERLINE = "\u001b[4;34m";
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: warcana search" +
                " [options] WARC_FILE_OR_COLLECTION [QUERY]");
        stream.println();
        stream.println(
                "Full-text searches Mastodon, Bluesky and legacy Twitter posts.");
        stream.println("With no query, lists the most recent posts first.");
        stream.println("The first search builds a reusable sidecar index. " +
                "Collections use");
        stream.println("their index/ directory and search every completed WARC.");
        stream.println("Terms are ANDed by default; quotes, OR, NOT and field queries");
        stream.println("(text:, author:, mentions:, alt:, platform:) are supported.");
        stream.println();
        stream.println("Options:");
        stream.println("  --index DIRECTORY  Use a custom index directory");
        stream.println("  --reindex          Rebuild the search index");
        stream.println("  --platform NAME    Filter by mastodon, bluesky or twitter");
        stream.println("  --author HANDLE    Filter by exact author handle");
        stream.println("  --since DATE       Include posts on or after DATE");
        stream.println("  --until DATE       Include posts on or before DATE");
        stream.println("  --has-media        Only include posts with attachments");
        stream.println("  --sort ORDER       relevance, newest or oldest");
        stream.println("  --limit NUMBER     Maximum results (default: 20)");
        stream.println("  --offset NUMBER    Skip the first NUMBER results");
        stream.println("  --format FORMAT    text, json or jsonl (default: text)");
        stream.println("  --color WHEN       auto, always or never (default: auto)");
        stream.println("  -q, --quiet        Suppress index progress output");
        stream.println("  -h, --help         Show this help");
    }

    private SearchCommand() {
    }
}
