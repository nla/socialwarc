package org.netpreserve.warcana;

import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.WarcJsonExtractor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExtractCommand {
    static int run(String[] args, PrintStream out, PrintStream err) {
        Path input = null;
        Path output = null;
        boolean force = false;
        boolean pretty = false;

        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        usage(out);
                        return 0;
                    }
                    case "-o", "--output" -> output = Path.of(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "-f", "--force" -> force = true;
                    case "--pretty" -> pretty = true;
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException(
                                    "Unknown option: " + args[i]);
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
                throw new IllegalArgumentException(
                        "WARC file does not exist: " + input);
            }
            if (output != null && Files.exists(output) && !force) {
                throw new IllegalArgumentException(
                        "Output already exists: " + output +
                                " (use --force to replace it)");
            }

            var results = new WarcJsonExtractor(
                    CliSupport.responseExtractors()).extract(input);
            byte[] json = pretty
                    ? SocialJson.mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsBytes(results)
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

    private static void usage(PrintStream stream) {
        stream.println("Usage: warcana extract [options] WARC_FILE");
        stream.println();
        stream.println("Extracts Mastodon, Bluesky, Threads and legacy Twitter" +
                " posts as common-model SocialResults JSON.");
        stream.println("Writes JSON to stdout unless --output is specified.");
        stream.println();
        stream.println("Options:");
        stream.println("  -o, --output FILE  Write JSON to FILE instead of stdout");
        stream.println("  --pretty           Pretty-print the JSON");
        stream.println("  -f, --force        Replace an existing output file");
        stream.println("  -h, --help         Show this help");
    }

    private ExtractCommand() {
    }
}
