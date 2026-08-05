package org.netpreserve.socialwarc;

import org.netpreserve.socialwarc.common.SocialJson;
import org.netpreserve.socialwarc.common.collection.CollectionStore;
import org.netpreserve.socialwarc.common.inspect.CollectionInspection;
import org.netpreserve.socialwarc.common.inspect.CollectionInspector;
import org.netpreserve.socialwarc.common.inspect.WarcInspection;
import org.netpreserve.socialwarc.common.inspect.WarcInspector;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class InspectCommand {
    private InspectCommand() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path input = null;
        InspectFormat format = InspectFormat.TEXT;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        usage(out);
                        return 0;
                    }
                    case "--format" -> format = InspectFormat.parse(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException(
                                    "Unknown option: " + args[i]);
                        }
                        if (input != null) {
                            throw new IllegalArgumentException(
                                    "Only one WARC file or collection may be inspected");
                        }
                        input = Path.of(args[i]);
                    }
                }
            }
            if (input == null) {
                throw new IllegalArgumentException(
                        "Missing WARC file or collection");
            }

            var store = new CollectionStore(input);
            Object inspection;
            if (Files.isDirectory(input) && store.isCollection()) {
                inspection = new CollectionInspector().inspect(store);
            } else if (Files.isRegularFile(input)) {
                inspection = new WarcInspector(CliSupport.responseExtractors()).inspect(input);
            } else {
                throw new IllegalArgumentException(
                        "WARC file or collection does not exist: " + input);
            }

            if (format == InspectFormat.JSON) {
                out.write(SocialJson.mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(inspection));
                out.println();
            } else if (inspection instanceof WarcInspection warc) {
                writeWarcInspection(warc, out);
            } else {
                writeCollectionInspection(
                        (CollectionInspection) inspection, out);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("inspection failed: " + e.getMessage());
            return 1;
        }
    }

    private static void writeWarcInspection(
            WarcInspection inspection, PrintStream out) {
        out.println("WARC: " + inspection.path());
        out.println("Size: " + CliSupport.humanBytes(inspection.sizeBytes()));
        out.println("Records: " + inspection.records());
        for (var entry : inspection.recordTypes().entrySet()) {
            out.printf("  %-14s %d%n", entry.getKey(), entry.getValue());
        }
        if (!inspection.httpStatuses().isEmpty()) {
            out.println("HTTP statuses:");
            for (var entry : inspection.httpStatuses().entrySet()) {
                out.printf("  %-14s %d%n", entry.getKey(), entry.getValue());
            }
        }
        if (inspection.earliestRecord() != null) {
            out.println("Record dates: " + inspection.earliestRecord() +
                    " to " + inspection.latestRecord());
        }
        out.println("Posts: " + inspection.posts());
        for (var entry : inspection.postsByPlatform().entrySet()) {
            out.printf("  %-14s %d%n", entry.getKey(), entry.getValue());
        }
        out.printf("Block digests: %d checked, %d mismatched%n",
                inspection.blockDigestsChecked(),
                inspection.blockDigestMismatches());
        out.println("Parser warnings: " + inspection.warnings().size());
        for (String warning : inspection.warnings()) {
            out.println("  " + warning);
        }
    }

    private static void writeCollectionInspection(
            CollectionInspection inspection, PrintStream out) {
        out.println("Collection: " + inspection.path());
        out.printf("Accounts: %d configured, %d tracked%n",
                inspection.configuredAccounts(),
                inspection.trackedAccounts());
        out.println("Active jobs: " + inspection.activeJobs());
        out.println("Search index: " +
                (inspection.indexPresent() ? "present" : "not built"));
        out.println("Completed WARCs: " +
                CliSupport.humanBytes(inspection.completedBytes()));
        for (var entry : inspection.completedByKind().entrySet()) {
            out.printf("  %-14s %d files, %s%n",
                    entry.getKey(),
                    entry.getValue().files(),
                    CliSupport.humanBytes(entry.getValue().bytes()));
        }
        long open = inspection.warcs().stream()
                .filter(file -> !"completed".equals(file.state()))
                .count();
        if (open > 0) {
            out.println("Open WARCs:");
            inspection.warcs().stream()
                    .filter(file -> !"completed".equals(file.state()))
                    .forEach(file -> out.printf(
                            "  %-8s %-8s %s  %s%n",
                            file.kind(),
                            file.state(),
                            CliSupport.humanBytes(file.sizeBytes()),
                            file.path()));
        }
    }

    private enum InspectFormat {
        TEXT,
        JSON;

        static InspectFormat parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown inspect format '" + value +
                                "' (expected text or json)");
            }
        }
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: socialwarc inspect [options] WARC_FILE_OR_COLLECTION");
        stream.println();
        stream.println("Reports WARC structure, HTTP statuses, post counts,");
        stream.println("digests and warnings, or collection accounts, WARC sets,");
        stream.println("open jobs and search-index state.");
        stream.println();
        stream.println("Options:");
        stream.println("  --format FORMAT  text or json (default: text)");
        stream.println("  -h, --help       Show this help");
    }
}
