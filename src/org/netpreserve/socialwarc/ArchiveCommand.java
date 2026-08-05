package org.netpreserve.socialwarc;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.socialwarc.bluesky.AtProtocolClient;
import org.netpreserve.socialwarc.bluesky.BlueskyAccountArchiver;
import org.netpreserve.socialwarc.bluesky.BlueskyAccountReference;
import org.netpreserve.socialwarc.common.ArchiveCheckpoint;
import org.netpreserve.socialwarc.common.ArchiveRunOptions;
import org.netpreserve.socialwarc.common.MediaCaptureStats;
import org.netpreserve.socialwarc.common.MediaCapturer;
import org.netpreserve.socialwarc.common.MediaSink;
import org.netpreserve.socialwarc.common.ProgressReporter;
import org.netpreserve.socialwarc.common.collection.CollectionJob;
import org.netpreserve.socialwarc.mastodon.MastodonAccountArchiver;
import org.netpreserve.socialwarc.mastodon.MastodonAccountReference;
import org.netpreserve.socialwarc.mastodon.MastodonLocalTimelineArchiver;
import org.netpreserve.socialwarc.mastodon.MastodonServerReference;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArchiveCommand {
    public static final String DEFAULT_USER_AGENT = "socialwarc/0.1.0";
    private static final String ARCHIVE_COMMAND = "archive";

    private ArchiveCommand() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, ProgressListener.NONE);
    }

    public static int run(
            String[] args, PrintStream out, PrintStream err,
            ProgressListener progressListener) {
        try {
            ArchiveOptions options = ArchiveOptions.parse(args);
            if (options.help()) {
                archiveUsage(out);
                return 0;
            }
            if (options.collection() != null) {
                return new CollectionArchiveRunner(
                        options, out, err, progressListener).run();
            }
            archiveAccount(options, out, err);
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("archive failed: " + e.getMessage());
            return 1;
        }
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void targetsScheduled(int targets) {
        }

        default void targetUpdated(TargetProgress progress) {
        }

        default void targetCompleted(TargetProgress progress) {
        }

        default void targetFailed(String key) {
        }
    }

    public record TargetProgress(
            String key, String account, int posts, int pages,
            int mediaCaptured, int mediaFailed, int mediaSkipped,
            long mediaBytes, int videoCaptured, int videoFailed,
            int videoSkipped, long videoBytes, int mediaQueued,
            int videoQueued) {
        static TargetProgress from(CollectionJob job) {
            return new TargetProgress(
                    job.key, job.account, job.postsArchived, job.pagesFetched,
                    job.mediaCaptured, job.mediaFailed, job.mediaSkipped,
                    job.mediaBytes, job.videoCaptured, job.videoFailed,
                    job.videoSkipped, job.videoBytes,
                    job.pendingMedia.size(), job.pendingVideo.size());
        }
    }

    private static void archiveAccount(
            ArchiveOptions options, PrintStream out, PrintStream err)
            throws IOException {
        ArchivePlatform platform = options.localTimeline()
                ? ArchivePlatform.MASTODON
                : options.platformFor(options.account());
        ProgressReporter progress = options.quiet()
                ? ProgressReporter.NONE
                : err::println;
        ArchiveRunOptions runOptions = new ArchiveRunOptions(
                options.since(),
                options.until(),
                options.maxPosts(),
                options.maxPages(),
                null,
                null,
                null,
                0,
                0);
        if (options.localTimeline()) {
            archiveMastodonLocalTimeline(options, runOptions, progress, out);
        } else if (platform == ArchivePlatform.MASTODON) {
            archiveMastodon(options, runOptions, progress, out);
        } else {
            archiveBluesky(options, runOptions, progress, out);
        }
    }

    private static void archiveMastodonLocalTimeline(
            ArchiveOptions options,
            ArchiveRunOptions runOptions,
            ProgressReporter progress,
            PrintStream out) throws IOException {
        if (options.service() != null) {
            throw new IllegalArgumentException(
                    "--service is only valid for Bluesky");
        }
        int pageSize = options.pageSize() == null ? 40 : options.pageSize();
        MastodonServerReference reference =
                MastodonServerReference.parse(options.account());
        Path output = chooseOutput(
                options.output(), reference.defaultFilename(), options.force());
        Path temporary = temporaryOutput(output);
        var result = archiveMastodonLocalTimeline(
                reference, temporary, output.getFileName().toString(),
                options.userAgent(), pageSize, options.includeMedia(),
                options.includeVideo(), options.allVideoVariants(),
                options.maxMediaBytes(), options.maxTotalMediaBytes(),
                progress, runOptions);
        moveCompletedWarc(temporary, output, options.force());
        out.printf("Archived %d local statuses from %s to %s%n",
                result.statusesArchived(), reference.server(),
                output.toAbsolutePath());
        printMediaSummary(out, options.includeMedia(), result.media());
    }

    private static void archiveMastodon(
            ArchiveOptions options,
            ArchiveRunOptions runOptions,
            ProgressReporter progress,
            PrintStream out) throws IOException {
        if (options.service() != null) {
            throw new IllegalArgumentException(
                    "--service is only valid for Bluesky");
        }
        int pageSize = options.pageSize() == null
                ? 40
                : options.pageSize();
        MastodonAccountReference reference =
                MastodonAccountReference.parse(options.account());
        Path output = chooseOutput(
                options.output(),
                reference.defaultFilename(),
                options.force());
        Path temporary = temporaryOutput(output);
        var result = archiveMastodon(
                reference,
                temporary,
                output.getFileName().toString(),
                options.userAgent(),
                pageSize,
                options.includeMedia(),
                options.includeVideo(),
                options.allVideoVariants(),
                options.maxMediaBytes(),
                options.maxTotalMediaBytes(),
                progress,
                runOptions);
        moveCompletedWarc(temporary, output, options.force());
        out.printf("Archived %d statuses from %s to %s%n",
                result.statusesArchived(),
                result.account().acct(),
                output.toAbsolutePath());
        printMediaSummary(
                out, options.includeMedia(), result.media());
    }

    private static void archiveBluesky(
            ArchiveOptions options,
            ArchiveRunOptions runOptions,
            ProgressReporter progress,
            PrintStream out) throws IOException {
        int pageSize = options.pageSize() == null
                ? 100
                : options.pageSize();
        String service = options.service() == null
                ? AtProtocolClient.PUBLIC_APPVIEW
                : options.service();
        BlueskyAccountReference reference =
                BlueskyAccountReference.parse(options.account());
        Path output = chooseOutput(
                options.output(),
                reference.defaultFilename(),
                options.force());
        Path temporary = temporaryOutput(output);
        var result = archiveBluesky(
                reference,
                temporary,
                output.getFileName().toString(),
                service,
                options.userAgent(),
                pageSize,
                options.includeMedia(),
                options.includeVideo(),
                options.allVideoVariants(),
                options.maxMediaBytes(),
                options.maxTotalMediaBytes(),
                progress,
                runOptions);
        moveCompletedWarc(temporary, output, options.force());
        out.printf("Archived %d feed items from %s to %s%n",
                result.feedItemsArchived(),
                result.profile().handle(),
                output.toAbsolutePath());
        printMediaSummary(
                out, options.includeMedia(), result.media());
    }

    private static Path chooseOutput(
            Path requested, String defaultFilename, boolean force) {
        Path output =
                requested == null ? Path.of(defaultFilename) : requested;
        if (Files.exists(output) && !force) {
            throw new IllegalArgumentException(
                    "Output already exists: " + output +
                            " (use --force to replace it)");
        }
        return output;
    }

    private static Path temporaryOutput(Path output) throws IOException {
        Path absolute = output.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return absolute.resolveSibling(
                ".open-" + UUID.randomUUID() + "-" +
                        absolute.getFileName());
    }

    private static void moveCompletedWarc(
            Path temporary, Path output, boolean replace)
            throws IOException {
        var options = replace
                ? new StandardCopyOption[]{
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                }
                : new StandardCopyOption[]{
                        StandardCopyOption.ATOMIC_MOVE
                };
        try {
            Files.move(temporary, output.toAbsolutePath(), options);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(
                    temporary,
                    output.toAbsolutePath(),
                    replace
                            ? new StandardCopyOption[]{
                                    StandardCopyOption.REPLACE_EXISTING
                            }
                            : new StandardCopyOption[]{});
        }
    }

    private static MastodonAccountArchiver.ArchiveResult archiveMastodon(
            MastodonAccountReference reference,
            Path output,
            String warcFilename,
            String userAgent,
            int pageSize,
            boolean includeMedia,
            boolean includeVideo,
            boolean allVideoVariants,
            long maxMediaBytes,
            long maxTotalMediaBytes,
            ProgressReporter progress,
            ArchiveRunOptions options) throws IOException {
        String accountAddress = reference.account() + "@" +
                URI.create(reference.server()).getRawAuthority();
        Map<String, List<String>> metadata = archiveMetadata(
                userAgent,
                "mastodon",
                accountAddress,
                includeMedia,
                includeVideo,
                allVideoVariants,
                maxMediaBytes,
                maxTotalMediaBytes);

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(warcFilename)
                    .fields(metadata)
                    .build());
            MediaSink mediaSink = includeMedia
                    ? new MediaCapturer(
                            warcWriter,
                            userAgent,
                            maxMediaBytes,
                            maxTotalMediaBytes,
                            allVideoVariants,
                            progress)
                    : null;
            return new MastodonAccountArchiver(
                    reference.server(),
                    userAgent,
                    warcWriter,
                    pageSize,
                    mediaSink,
                    progress,
                    includeVideo)
                    .archive(
                            reference.account(),
                            options,
                            ArchiveCheckpoint.NONE);
        }
    }

    private static MastodonLocalTimelineArchiver.ArchiveResult
            archiveMastodonLocalTimeline(
                    MastodonServerReference reference,
                    Path output,
                    String warcFilename,
                    String userAgent,
                    int pageSize,
                    boolean includeMedia,
                    boolean includeVideo,
                    boolean allVideoVariants,
                    long maxMediaBytes,
                    long maxTotalMediaBytes,
                    ProgressReporter progress,
                    ArchiveRunOptions options) throws IOException {
        Map<String, List<String>> metadata = archiveMetadata(
                userAgent, "mastodon", reference.server(), includeMedia,
                includeVideo, allVideoVariants, maxMediaBytes,
                maxTotalMediaBytes);
        metadata.remove("account");
        metadata.put("scope", List.of("local-public-timeline"));
        metadata.put("server", List.of(reference.server()));

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(warcFilename)
                    .fields(metadata)
                    .build());
            MediaSink mediaSink = includeMedia
                    ? new MediaCapturer(
                            warcWriter, userAgent, maxMediaBytes,
                            maxTotalMediaBytes, allVideoVariants, progress)
                    : null;
            return new MastodonLocalTimelineArchiver(
                    reference.server(), userAgent, warcWriter, pageSize,
                    mediaSink, progress, includeVideo)
                    .archive(options, ArchiveCheckpoint.NONE);
        }
    }

    private static BlueskyAccountArchiver.ArchiveResult archiveBluesky(
            BlueskyAccountReference reference,
            Path output,
            String warcFilename,
            String service,
            String userAgent,
            int pageSize,
            boolean includeMedia,
            boolean includeVideo,
            boolean allVideoVariants,
            long maxMediaBytes,
            long maxTotalMediaBytes,
            ProgressReporter progress,
            ArchiveRunOptions options) throws IOException {
        Map<String, List<String>> metadata = archiveMetadata(
                userAgent,
                "bluesky",
                reference.actor(),
                includeMedia,
                includeVideo,
                allVideoVariants,
                maxMediaBytes,
                maxTotalMediaBytes);
        metadata.put("service", List.of(service));

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(warcFilename)
                    .fields(metadata)
                    .build());
            MediaSink mediaSink = includeMedia
                    ? new MediaCapturer(
                            warcWriter,
                            userAgent,
                            maxMediaBytes,
                            maxTotalMediaBytes,
                            allVideoVariants,
                            progress)
                    : null;
            return new BlueskyAccountArchiver(
                    service,
                    userAgent,
                    warcWriter,
                    pageSize,
                    mediaSink,
                    progress,
                    includeVideo)
                    .archive(
                            reference.actor(),
                            options,
                            ArchiveCheckpoint.NONE);
        }
    }

    static Map<String, List<String>> archiveMetadata(
            String userAgent,
            String platform,
            String account,
            boolean includeMedia,
            boolean includeVideo,
            boolean allVideoVariants,
            long maxMediaBytes,
            long maxTotalMediaBytes) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("software", List.of("socialwarc/0.1.0"));
        metadata.put("user-agent", List.of(userAgent));
        metadata.put("platform", List.of(platform));
        metadata.put("account", List.of(account));
        metadata.put("media", List.of(
                includeMedia ? "included" : "excluded"));
        if (includeMedia) {
            metadata.put(
                    "media-max-resource-bytes",
                    List.of(Long.toString(maxMediaBytes)));
            metadata.put(
                    "media-max-total-bytes",
                    List.of(Long.toString(maxTotalMediaBytes)));
            metadata.put(
                    "video",
                    List.of(includeVideo ? "included" : "excluded"));
            if (includeVideo) {
                metadata.put(
                        "video-variants",
                        List.of(allVideoVariants
                                ? "all"
                                : "highest-bandwidth"));
            }
        }
        metadata.put("created", List.of(Instant.now().toString()));
        return metadata;
    }

    static void printMediaSummary(
            PrintStream out,
            boolean includeMedia,
            MediaCaptureStats stats) {
        if (includeMedia) {
            out.printf(
                    "Captured %d media resources (%d bytes); " +
                            "%d failed, %d skipped%n",
                    stats.captured(),
                    stats.bytes(),
                    stats.failed(),
                    stats.skipped());
        }
    }

    static long parseByteSize(String value) {
        return ArchiveOptions.parseByteSize(value);
    }

    private static void archiveUsage(PrintStream stream) {
        stream.println(
                "Usage: socialwarc " + ARCHIVE_COMMAND +
                        " [options] TARGET");
        stream.println(
                "       socialwarc " + ARCHIVE_COMMAND +
                        " --collection DIRECTORY [options]");
        stream.println();
        stream.println(
                "Archives a Mastodon or Bluesky account, or a Mastodon local timeline.");
        stream.println(
                "Use --local-timeline to archive posts originating on a Mastodon server.");
        stream.println(
                "Use --platform when an account handle is ambiguous.");
        stream.println();
        stream.println("Options:");
        stream.println(
                "  -p, --platform NAME    mastodon or bluesky");
        stream.println(
                "  -C, --collection DIR   Archive every entry in " +
                        "DIR/accounts.txt");
        stream.println(
                "  -o, --output FILE      Output path");
        stream.println(
                "  -A, --user-agent TEXT  HTTP User-Agent (default: " +
                        DEFAULT_USER_AGENT + ")");
        stream.println(
                "  --page-size NUMBER     Items per API request");
        stream.println(
                "  --local-timeline       Archive a Mastodon server's local public timeline");
        stream.println(
                "  --service URL          Bluesky AT Protocol AppView");
        stream.println(
                "  --include-media        Capture linked media into " +
                        "the WARC");
        stream.println(
                "  --all-video-variants   Capture every HLS variant " +
                        "instead of only");
        stream.println(
                "                         the highest-bandwidth variant");
        stream.println(
                "  --exclude-video        Capture other media and " +
                        "video previews,");
        stream.println(
                "                         but omit video content");
        stream.println(
                "  --max-media-bytes N    Per-resource limit " +
                        "(default: 100M)");
        stream.println(
                "  --max-total-media-bytes N");
        stream.println(
                "                         Total media limit " +
                        "(default: 1G)");
        stream.println(
                "  --since DATE           Stop before posts older " +
                        "than DATE");
        stream.println(
                "  --until DATE           Skip posts newer than DATE");
        stream.println(
                "  --max-posts NUMBER     Stop after NUMBER selected " +
                        "posts");
        stream.println(
                "  --max-pages NUMBER     Stop after NUMBER API pages");
        stream.println(
                "  -f, --force            Replace an existing output " +
                        "file");
        stream.println(
                "  -q, --quiet            Suppress progress output");
        stream.println(
                "  -h, --help             Show this help");
    }
}
