package org.netpreserve.socialwarc;

import java.io.PrintStream;

public class SocialWarc {
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
            case "archive" -> ArchiveCommand.run(args, out, err);
            case "extract" -> ExtractCommand.run(args, out, err);
            case "search" -> SearchCommand.run(args, out, err);
            case "serve" -> ServeCommand.run(args, out, err);
            case "inspect" -> InspectCommand.run(args, out, err);
            case "init" -> InitCommand.run(args, out, err);
            default -> {
                err.println("Unknown command: " + args[0]);
                usage(err);
                yield 2;
            }
        };
    }

    static long parseByteSize(String value) {
        return ArchiveCommand.parseByteSize(value);
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: socialwarc <command> [options]");
        stream.println();
        stream.println("Commands:");
        stream.printf("  %-12s %s%n", "init",
                "Initialize an append-only collection directory");
        stream.printf("  %-12s %s%n", "archive",
                "Archive a social account or Mastodon local timeline to WARC");
        stream.printf("  %-12s %s%n", "extract",
                "Extract common-model JSON from a WARC");
        stream.printf("  %-12s %s%n", "search",
                "Full-text search posts in a WARC");
        stream.printf("  %-12s %s%n", "serve",
                "Browse and search a collection locally");
        stream.printf("  %-12s %s%n", "inspect",
                "Inspect a WARC file or collection");
        stream.println();
        stream.println("Run 'socialwarc <command> --help' for command options.");
    }
}
