package org.netpreserve.warcana;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.warcana.bluesky.AtProtocolClient;
import org.netpreserve.warcana.bluesky.BlueskyAccountArchiver;
import org.netpreserve.warcana.bluesky.BlueskyAccountReference;
import org.netpreserve.warcana.bluesky.BlueskyWarcResponseExtractor;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.WarcJsonExtractor;
import org.netpreserve.warcana.common.WarcResponseExtractor;
import org.netpreserve.warcana.mastodon.MastodonAccountArchiver;
import org.netpreserve.warcana.mastodon.MastodonAccountReference;
import org.netpreserve.warcana.mastodon.MastodonWarcResponseExtractor;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Warcana {
    private static final String ARCHIVE_COMMAND = "archive";
    private static final String EXTRACT_COMMAND = "extract";
    private static final String DEFAULT_USER_AGENT = "warcana/0.1.0";

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
                    case "-f", "--force" -> force = true;
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
            Platform platform = requestedPlatform != null
                    ? requestedPlatform
                    : detectPlatform(accountArgument);

            if (platform == Platform.MASTODON) {
                if (service != null) {
                    throw new IllegalArgumentException("--service is only valid for Bluesky");
                }
                int effectivePageSize = pageSize == null ? 40 : pageSize;
                MastodonAccountReference reference = MastodonAccountReference.parse(accountArgument);
                output = chooseOutput(output, reference.defaultFilename(), force);
                var result = archiveMastodon(reference, output, userAgent, effectivePageSize);
                out.printf("Archived %d statuses from %s to %s%n",
                        result.statusesArchived(), result.account().acct(), output.toAbsolutePath());
            } else {
                int effectivePageSize = pageSize == null ? 100 : pageSize;
                String effectiveService = service == null
                        ? AtProtocolClient.PUBLIC_APPVIEW
                        : service;
                BlueskyAccountReference reference = BlueskyAccountReference.parse(accountArgument);
                output = chooseOutput(output, reference.defaultFilename(), force);
                var result = archiveBluesky(
                        reference, output, effectiveService, userAgent, effectivePageSize);
                out.printf("Archived %d feed items from %s to %s%n",
                        result.feedItemsArchived(), result.profile().handle(), output.toAbsolutePath());
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

            List<WarcResponseExtractor> extractors = List.of(
                    new MastodonWarcResponseExtractor(),
                    new BlueskyWarcResponseExtractor());
            var results = new WarcJsonExtractor(extractors).extract(input);
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

    private static MastodonAccountArchiver.ArchiveResult archiveMastodon(
            MastodonAccountReference reference, Path output,
            String userAgent, int pageSize) throws IOException {
        String accountAddress = reference.account() + "@" +
                URI.create(reference.server()).getRawAuthority();
        Map<String, List<String>> metadata = Map.of(
                "software", List.of(userAgent),
                "format", List.of("WARC File Format 1.1"),
                "platform", List.of("mastodon"),
                "account", List.of(accountAddress),
                "created", List.of(Instant.now().toString()));

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(output.getFileName().toString())
                    .fields(metadata)
                    .build());
            return new MastodonAccountArchiver(
                    reference.server(), userAgent, warcWriter, pageSize)
                    .archive(reference.account());
        }
    }

    private static BlueskyAccountArchiver.ArchiveResult archiveBluesky(
            BlueskyAccountReference reference, Path output, String service,
            String userAgent, int pageSize) throws IOException {
        Map<String, List<String>> metadata = Map.of(
                "software", List.of(userAgent),
                "format", List.of("WARC File Format 1.1"),
                "platform", List.of("bluesky"),
                "account", List.of(reference.actor()),
                "api", List.of(service),
                "created", List.of(Instant.now().toString()));

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(output.getFileName().toString())
                    .fields(metadata)
                    .build());
            return new BlueskyAccountArchiver(
                    service, userAgent, warcWriter, pageSize)
                    .archive(reference.actor());
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
        stream.println("  -f, --force            Replace an existing output file");
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
}
