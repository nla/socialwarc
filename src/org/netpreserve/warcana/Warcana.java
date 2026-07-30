package org.netpreserve.warcana;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.warcana.mastodon.MastodonAccountArchiver;
import org.netpreserve.warcana.mastodon.MastodonAccountReference;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class Warcana {
    private static final String COMMAND = "archive-mastodon-account";
    private static final String DEFAULT_USER_AGENT = "warcana/0.1.0";

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
        if (!COMMAND.equals(args[0])) {
            err.println("Unknown command: " + args[0]);
            usage(err);
            return 2;
        }

        Path output = null;
        String userAgent = DEFAULT_USER_AGENT;
        String accountArgument = null;
        int pageSize = 40;
        boolean force = false;

        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        archiveUsage(out);
                        return 0;
                    }
                    case "-o", "--output" -> output = Path.of(requireValue(args, ++i, args[i - 1]));
                    case "-A", "--user-agent" -> userAgent = requireValue(args, ++i, args[i - 1]);
                    case "--page-size" ->
                            pageSize = Integer.parseInt(requireValue(args, ++i, args[i - 1]));
                    case "-f", "--force" -> force = true;
                    default -> {
                        if (args[i].startsWith("-") && !args[i].startsWith("@")) {
                            throw new IllegalArgumentException("Unknown option: " + args[i]);
                        }
                        if (accountArgument != null) {
                            throw new IllegalArgumentException("Only one account may be archived at a time");
                        }
                        accountArgument = args[i];
                    }
                }
            }

            if (accountArgument == null) {
                throw new IllegalArgumentException("Missing Mastodon account");
            }
            MastodonAccountReference reference = MastodonAccountReference.parse(accountArgument);
            if (output == null) {
                output = Path.of(reference.defaultFilename());
            }
            if (Files.exists(output) && !force) {
                throw new IllegalArgumentException(
                        "Output already exists: " + output + " (use --force to replace it)");
            }

            var result = archive(reference, output, userAgent, pageSize);
            out.printf("Archived %d statuses from %s to %s%n",
                    result.statusesArchived(),
                    result.account().acct(),
                    output.toAbsolutePath());
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("archive failed: " + e.getMessage());
            return 1;
        }
    }

    private static MastodonAccountArchiver.ArchiveResult archive(
            MastodonAccountReference reference, Path output,
            String userAgent, int pageSize) throws IOException {
        String accountAddress = reference.account() + "@" +
                java.net.URI.create(reference.server()).getRawAuthority();
        Map<String, List<String>> metadata = Map.of(
                "software", List.of(userAgent),
                "format", List.of("WARC File Format 1.1"),
                "account", List.of(accountAddress),
                "created", List.of(Instant.now().toString()));

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(output.getFileName().toString())
                    .fields(metadata)
                    .build());
            var archiver = new MastodonAccountArchiver(
                    reference.server(), userAgent, warcWriter, pageSize);
            return archiver.archive(reference.account());
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
        stream.println("  " + COMMAND + "  Archive a Mastodon account to WARC");
        stream.println();
        stream.println("Run 'warcana " + COMMAND + " --help' for command options.");
    }

    private static void archiveUsage(PrintStream stream) {
        stream.println("Usage: warcana " + COMMAND + " [options] ACCOUNT");
        stream.println();
        stream.println("ACCOUNT may be @user@server or https://server/@user.");
        stream.println();
        stream.println("Options:");
        stream.println("  -o, --output FILE       Output path (default: user@server.warc)");
        stream.println("  -A, --user-agent VALUE  HTTP User-Agent (default: " + DEFAULT_USER_AGENT + ")");
        stream.println("  --page-size NUMBER      Statuses per API request, 1-40 (default: 40)");
        stream.println("  -f, --force             Replace an existing output file");
        stream.println("  -h, --help              Show this help");
    }
}
