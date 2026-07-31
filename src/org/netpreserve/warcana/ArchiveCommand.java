package org.netpreserve.warcana;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.WarcCompression;
import org.netpreserve.jwarc.Warcinfo;
import org.jsoup.nodes.Entities;
import org.netpreserve.warcana.bluesky.AtProtocolClient;
import org.netpreserve.warcana.bluesky.BlueskyAccountArchiver;
import org.netpreserve.warcana.bluesky.BlueskyAccountReference;
import org.netpreserve.warcana.common.MediaCapturer;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.ArchiveCheckpoint;
import org.netpreserve.warcana.common.ArchiveRunOptions;
import org.netpreserve.warcana.common.collection.CollectionAccount;
import org.netpreserve.warcana.common.collection.CollectionJob;
import org.netpreserve.warcana.common.collection.CollectionState;
import org.netpreserve.warcana.common.collection.CollectionStore;
import org.netpreserve.warcana.common.collection.SplitCheckpointingMediaSink;
import org.netpreserve.warcana.mastodon.MastodonAccountArchiver;
import org.netpreserve.warcana.mastodon.MastodonAccountReference;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

class ArchiveCommand {
    private static final String ARCHIVE_COMMAND = "archive";
    private static final String DEFAULT_USER_AGENT = "warcana/0.1.0";
    private static final long DEFAULT_MAX_MEDIA_BYTES = 100L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_MEDIA_BYTES = 1024L * 1024 * 1024;
    private static final long DEFAULT_MAX_WARC_BYTES = 1024L * 1024 * 1024;

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

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path output = null;
        Path collection = null;
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
        Instant since = null;
        Instant until = null;
        int maxPosts = 0;
        int maxPages = 0;

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
                    case "-C", "--collection" ->
                            collection = Path.of(requireValue(args, ++i, args[i - 1]));
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
                    case "--since" ->
                            since = CliSupport.parseDate(
                                    requireValue(args, ++i, args[i - 1]), false);
                    case "--until" ->
                            until = CliSupport.parseDate(
                                    requireValue(args, ++i, args[i - 1]), true);
                    case "--max-posts" ->
                            maxPosts = Integer.parseInt(requireValue(args, ++i, args[i - 1]));
                    case "--max-pages" ->
                            maxPages = Integer.parseInt(requireValue(args, ++i, args[i - 1]));
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

            if (collection == null && accountArgument == null) {
                throw new IllegalArgumentException("Missing account");
            }
            if (collection != null && accountArgument != null) {
                throw new IllegalArgumentException(
                        "ACCOUNT is read from accounts.txt when --collection is used");
            }
            if (collection != null && output != null) {
                throw new IllegalArgumentException(
                        "--output cannot be used with --collection");
            }
            if (excludeVideo && allVideoVariants) {
                throw new IllegalArgumentException(
                        "--exclude-video and --all-video-variants cannot be used together");
            }
            if (since != null && until != null && since.isAfter(until)) {
                throw new IllegalArgumentException("--since must not be after --until");
            }
            if (maxPosts < 0 || maxPages < 0) {
                throw new IllegalArgumentException(
                        "--max-posts and --max-pages must not be negative");
            }
            if (collection != null) {
                return archiveCollection(
                        collection,
                        userAgent,
                        service,
                        pageSize,
                        requestedPlatform,
                        includeMedia,
                        !excludeVideo,
                        allVideoVariants,
                        maxMediaBytes,
                        maxTotalMediaBytes,
                        since,
                        until,
                        maxPosts,
                        maxPages,
                        quiet,
                        out,
                        err);
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
                Path temporary = temporaryOutput(output);
                var result = archiveMastodon(
                        reference, temporary, output.getFileName().toString(),
                        userAgent, effectivePageSize,
                        includeMedia, !excludeVideo, allVideoVariants,
                        maxMediaBytes, maxTotalMediaBytes, progress,
                        new ArchiveRunOptions(
                                since, until, maxPosts, maxPages,
                                null, null, null, 0, 0));
                moveCompletedWarc(temporary, output, force);
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
                Path temporary = temporaryOutput(output);
                var result = archiveBluesky(
                        reference, temporary, output.getFileName().toString(),
                        effectiveService, userAgent, effectivePageSize,
                        includeMedia, !excludeVideo, allVideoVariants,
                        maxMediaBytes, maxTotalMediaBytes, progress,
                        new ArchiveRunOptions(
                                since, until, maxPosts, maxPages,
                                null, null, null, 0, 0));
                moveCompletedWarc(temporary, output, force);
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

    private static int archiveCollection(
            Path root,
            String userAgent,
            String service,
            Integer requestedPageSize,
            Platform requestedPlatform,
            boolean includeMedia,
            boolean includeVideo,
            boolean allVideoVariants,
            long maxMediaBytes,
            long maxTotalMediaBytes,
            Instant since,
            Instant until,
            int maxPosts,
            int maxPages,
            boolean quiet,
            PrintStream out,
            PrintStream err) throws IOException {
        var store = new CollectionStore(root);
        store.initialize();
        List<CollectionAccount> accounts = store.readAccounts();
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Collection has no accounts; edit " + store.accountsFile());
        }

        try (var lockChannel = FileChannel.open(
                store.lockFile(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock acquiredLock;
            try {
                acquiredLock = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                acquiredLock = null;
            }
            if (acquiredLock == null) {
                throw new IOException(
                        "Collection is already being archived: " + store.root());
            }
            final java.nio.channels.FileLock lock = acquiredLock;
            try (lock) {
                var orderedAccounts = new ArrayList<>(accounts);
                for (int i = 0; i < orderedAccounts.size(); i++) {
                    CollectionAccount candidate = orderedAccounts.get(i);
                    Platform candidatePlatform = candidate.platform() == null
                            ? (requestedPlatform == null
                                    ? detectPlatform(candidate.account())
                                    : requestedPlatform)
                            : Platform.parse(candidate.platform());
                    String candidateKey = CollectionStore.accountKey(
                            candidatePlatform.name().toLowerCase(Locale.ROOT),
                            candidate.account());
                    if (Files.exists(store.jobFile(candidateKey))) {
                        if (i > 0) {
                            orderedAccounts.remove(i);
                            orderedAccounts.add(0, candidate);
                        }
                        break;
                    }
                }
                int succeeded = 0;
                int failed = 0;
                for (int accountIndex = 0;
                     accountIndex < orderedAccounts.size();
                     accountIndex++) {
                    CollectionAccount configured = orderedAccounts.get(accountIndex);
                    Platform platform = configured.platform() == null
                            ? (requestedPlatform == null
                                    ? detectPlatform(configured.account())
                                    : requestedPlatform)
                            : Platform.parse(configured.platform());
                    if (requestedPlatform != null && configured.platform() != null &&
                            platform != requestedPlatform) {
                        continue;
                    }
                    try {
                        archiveCollectionAccount(
                                store,
                                configured.account(),
                                platform,
                                userAgent,
                                service,
                                requestedPageSize,
                                includeMedia,
                                includeVideo,
                                allVideoVariants,
                                maxMediaBytes,
                                maxTotalMediaBytes,
                                since,
                                until,
                                maxPosts,
                                maxPages,
                                quiet ? ProgressReporter.NONE : err::println,
                                out);
                        succeeded++;
                    } catch (IOException | RuntimeException e) {
                        failed++;
                        Throwable cause = e instanceof java.io.UncheckedIOException
                                ? e.getCause()
                                : e;
                        String message = cause.getMessage() == null
                                ? cause.getClass().getSimpleName()
                                : cause.getMessage();
                        err.println("archive failed for " + configured.account() +
                                ": " + message);
                        store.log("FAILED " + platform.name().toLowerCase(Locale.ROOT) +
                                " " + configured.account() + " " + message);
                        err.println("The shared WARC remains open; rerun this command " +
                                "to resume before later accounts are archived.");
                        break;
                    }
                    CollectionState currentState = store.loadCollectionState();
                    if (currentState.openWarc != null &&
                            currentState.openCommittedOffset >= DEFAULT_MAX_WARC_BYTES &&
                            accountIndex + 1 < orderedAccounts.size()) {
                        Path rolled = finalizeOpenCollectionWarc(store, currentState);
                        out.println("Closed collection WARC at size limit: " + rolled);
                    }
                }
                if (failed == 0) {
                    CollectionState currentState = store.loadCollectionState();
                    for (Path completed : finalizeOpenCollectionWarcs(
                            store, currentState)) {
                        out.println("Completed collection WARC: " + completed);
                    }
                }
                out.printf("Collection run complete: %d succeeded, %d failed%n",
                        succeeded, failed);
                return failed == 0 ? 0 : 1;
            }
        }
    }

    private static void archiveCollectionAccount(
            CollectionStore store,
            String accountArgument,
            Platform platform,
            String userAgent,
            String requestedService,
            Integer requestedPageSize,
            boolean includeMedia,
            boolean includeVideo,
            boolean allVideoVariants,
            long maxMediaBytes,
            long maxTotalMediaBytes,
            Instant since,
            Instant until,
            int maxPosts,
            int maxPages,
            ProgressReporter progress,
            PrintStream out) throws IOException {
        String platformName = platform.name().toLowerCase(Locale.ROOT);
        String key = CollectionStore.accountKey(platformName, accountArgument);
        CollectionState collectionState = store.loadCollectionState();
        CollectionState.AccountProgress previous = collectionState.accounts.get(key);
        CollectionJob job = store.loadJob(key);
        if (job != null && job.openWarc != null &&
                job.openWarc.endsWith(".partial")) {
            String legacyName = job.openWarc;
            Path oldOpen = store.root().resolve(job.openWarc);
            String migratedName = job.openWarc.substring(
                    0, job.openWarc.length() - ".partial".length()) + ".open";
            Path migratedOpen = store.root().resolve(migratedName);
            if (Files.exists(oldOpen)) {
                if (Files.exists(migratedOpen)) {
                    throw new IOException(
                            "Both legacy and current open WARCs exist: " +
                                    oldOpen + " and " + migratedOpen);
                }
                Files.move(oldOpen, migratedOpen);
            }
            job.openWarc = migratedName;
            store.saveJob(job);
            if (legacyName.equals(collectionState.openWarc)) {
                collectionState.openWarc = migratedName;
                store.saveCollectionState(collectionState);
            }
            store.log("MIGRATE-OPEN " + platformName + " " +
                    accountArgument + " " + migratedName);
        }
        if (job != null &&
                Files.exists(store.root().resolve(job.openWarc)) &&
                !Path.of(job.openWarc).getFileName().toString()
                        .startsWith("warcana-posts-")) {
            String legacyOpen = job.openWarc;
            String legacyFinal = job.finalWarc;
            Path legacyOpenPath = store.root().resolve(legacyOpen);
            Path openPath = legacyOpenPath.resolveSibling(
                    "warcana-posts-" + legacyOpenPath.getFileName());
            if (Files.exists(legacyOpenPath)) {
                Files.move(legacyOpenPath, openPath);
            }
            job.openWarc = store.root().relativize(openPath).toString();
            Path legacyFinalPath = store.root().resolve(legacyFinal);
            job.finalWarc = store.root().relativize(
                    legacyFinalPath.resolveSibling(
                            "warcana-posts-" + legacyFinalPath.getFileName()))
                    .toString();
            if (legacyOpen.equals(collectionState.openWarc)) {
                collectionState.openWarc = job.openWarc;
                collectionState.finalWarc = job.finalWarc;
                store.saveCollectionState(collectionState);
            }
            store.saveJob(job);
            store.log("MIGRATE-POSTS-NAME " + platformName + " " +
                    accountArgument + " " + job.openWarc);
        }

        if (job != null && Files.exists(store.root().resolve(job.finalWarc))) {
            finalizeCollectionJob(store, collectionState, job);
            store.log("RECOVERED-COMPLETE " + platformName + " " +
                    accountArgument + " warc=" + job.finalWarc);
            out.printf("Recovered completed archive for %s: %s%n",
                    accountArgument, store.root().resolve(job.finalWarc));
            return;
        }

        boolean resumed = job != null;
        if (job == null) {
            job = new CollectionJob();
            job.key = key;
            job.platform = platformName;
            job.account = accountArgument;
            job.stopIdentity = previous == null ? null : previous.latestIdentity;
            job.since = since == null ? null : since.toString();
            job.until = until == null ? null : until.toString();
            job.maxPosts = maxPosts;
            job.maxPages = maxPages;
            job.includeMedia = includeMedia;
            job.includeVideo = includeVideo;
            job.allVideoVariants = allVideoVariants;
            job.maxMediaBytes = maxMediaBytes;
            job.maxTotalMediaBytes = maxTotalMediaBytes;
            job.pageSize = requestedPageSize == null
                    ? (platform == Platform.MASTODON ? 40 : 100)
                    : requestedPageSize;
            job.userAgent = userAgent;
            job.service = requestedService == null
                    ? AtProtocolClient.PUBLIC_APPVIEW
                    : requestedService;
            job.startedAt = Instant.now().toString();
            if (collectionState.openWarc == null) {
                String timestamp = DateTimeFormatter
                        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
                String basename = "warcana-posts-" + timestamp + "-" +
                        UUID.randomUUID().toString().substring(0, 8) + ".warc";
                collectionState.finalWarc = "warcs/" + basename;
                collectionState.openWarc = "warcs/" + basename + ".open";
                collectionState.openCommittedOffset = 0;
                store.saveCollectionState(collectionState);
            }
            job.finalWarc = collectionState.finalWarc;
            job.openWarc = collectionState.openWarc;
            job.committedOffset = collectionState.openCommittedOffset;
            store.saveJob(job);
            store.log("START " + platformName + " " + accountArgument +
                    " " + job.openWarc);
        } else {
            if (collectionState.openWarc == null) {
                collectionState.openWarc = job.openWarc;
                collectionState.finalWarc = job.finalWarc;
                collectionState.openCommittedOffset = job.committedOffset;
                store.saveCollectionState(collectionState);
            } else if (!collectionState.openWarc.equals(job.openWarc)) {
                throw new IOException(
                        "Another interrupted account owns the shared WARC: " +
                                collectionState.openWarc);
            }
            progress.report("Resuming " + accountArgument + " at WARC offset " +
                    job.committedOffset);
            store.log("RESUME " + platformName + " " + accountArgument +
                    " offset=" + job.committedOffset);
        }

        CollectionJob activeJob = job;
        Function<String, Warcinfo> warcinfoFactory = filename -> {
            Map<String, List<String>> metadata = archiveMetadata(
                    activeJob.userAgent,
                    platformName,
                    accountArgument,
                    activeJob.includeMedia,
                    activeJob.includeVideo,
                    activeJob.allVideoVariants,
                    activeJob.maxMediaBytes,
                    activeJob.maxTotalMediaBytes);
            metadata.put("collection", List.of(store.root().toString()));
            metadata.put("incremental-stop-identity",
                    List.of(activeJob.stopIdentity == null
                            ? ""
                            : activeJob.stopIdentity));
            return new Warcinfo.Builder()
                    .filename(filename)
                    .fields(metadata)
                    .build();
        };
        try (var writer = new RollingCollectionWarcWriter(
                store,
                collectionState,
                activeJob,
                RollingCollectionWarcWriter.Stream.POSTS,
                warcinfoFactory,
                progress)) {
                if (!resumed) {
                    writer.write(warcinfoFactory.apply(
                            Path.of(job.finalWarc).getFileName().toString()));
                    job.committedOffset = writer.position();
                    store.saveJob(job);
                }

                SplitCheckpointingMediaSink mediaSink = job.includeMedia
                        ? new SplitCheckpointingMediaSink(
                                store,
                                job,
                                () -> new RollingCollectionWarcWriter(
                                        store,
                                        collectionState,
                                        activeJob,
                                        RollingCollectionWarcWriter.Stream.MEDIA,
                                        warcinfoFactory,
                                        progress),
                                () -> new RollingCollectionWarcWriter(
                                        store,
                                        collectionState,
                                        activeJob,
                                        RollingCollectionWarcWriter.Stream.VIDEO,
                                        warcinfoFactory,
                                        progress),
                                job.userAgent,
                                job.maxMediaBytes,
                                job.maxTotalMediaBytes,
                                job.allVideoVariants,
                                progress)
                        : null;
                try (mediaSink) {
                if (mediaSink != null &&
                        (!job.pendingMedia.isEmpty() ||
                                !job.pendingVideo.isEmpty())) {
                    progress.report("Resuming " +
                            (job.pendingMedia.size() + job.pendingVideo.size()) +
                            " pending media resources...");
                    mediaSink.drainPending();
                }

                ArchiveRunOptions options = new ArchiveRunOptions(
                        job.since == null ? null : Instant.parse(job.since),
                        job.until == null ? null : Instant.parse(job.until),
                        job.maxPosts,
                        job.maxPages,
                        job.stopIdentity,
                        job.cursor,
                        job.newestIdentity,
                        job.postsArchived,
                        job.pagesFetched);
                ArchiveCheckpoint checkpoint = page -> {
                    activeJob.cursor = page.cursor();
                    activeJob.newestIdentity = page.newestIdentity();
                    activeJob.postsArchived = page.postsArchived();
                    activeJob.pagesFetched = page.pagesFetched();
                    activeJob.committedOffset = writer.position();
                    store.saveJob(activeJob);
                };

                if (platform == Platform.MASTODON) {
                    MastodonAccountReference reference =
                            MastodonAccountReference.parse(accountArgument);
                    new MastodonAccountArchiver(
                            reference.server(),
                            job.userAgent,
                            writer,
                            job.pageSize,
                            mediaSink,
                            progress,
                            job.includeVideo)
                            .archive(reference.account(), options, checkpoint);
                } else {
                    BlueskyAccountReference reference =
                            BlueskyAccountReference.parse(accountArgument);
                    new BlueskyAccountArchiver(
                            job.service,
                            job.userAgent,
                            writer,
                            job.pageSize,
                            mediaSink,
                            progress,
                            job.includeVideo)
                            .archive(reference.actor(), options, checkpoint);
                }
                job.committedOffset = writer.position();
                store.saveJob(job);
                }
        }

        collectionState.openCommittedOffset = job.committedOffset;
        collectionState.mediaOpenWarc = job.mediaOpenWarc;
        collectionState.mediaFinalWarc = job.mediaFinalWarc;
        collectionState.mediaCommittedOffset = job.mediaCommittedOffset;
        collectionState.videoOpenWarc = job.videoOpenWarc;
        collectionState.videoFinalWarc = job.videoFinalWarc;
        collectionState.videoCommittedOffset = job.videoCommittedOffset;
        finalizeCollectionJob(store, collectionState, job);
        store.log("ACCOUNT-COMPLETE " + platformName + " " + accountArgument +
                " posts=" + job.postsArchived + " openWarc=" + job.openWarc);
        out.printf("%sArchived %d posts from %s into %s%n",
                resumed ? "Resumed and " : "",
                job.postsArchived,
                accountArgument,
                store.root().resolve(job.openWarc));
        printMediaSummary(out, job.includeMedia, new MediaCaptureStats(
                job.mediaCaptured,
                job.mediaFailed,
                job.mediaSkipped,
                job.mediaBytes));
    }

    private static void finalizeCollectionJob(
            CollectionStore store,
            CollectionState collectionState,
            CollectionJob job) throws IOException {
        var progress = new CollectionState.AccountProgress();
        progress.platform = job.platform;
        progress.account = job.account;
        CollectionState.AccountProgress previous =
                collectionState.accounts.get(job.key);
        progress.latestIdentity = job.newestIdentity != null
                ? job.newestIdentity
                : previous == null ? null : previous.latestIdentity;
        progress.updatedAt = Instant.now().toString();
        collectionState.accounts.put(job.key, progress);
        store.saveCollectionState(collectionState);
        store.deleteJob(job.key);
    }

    private static Path finalizeOpenCollectionWarc(
            CollectionStore store,
            CollectionState state) throws IOException {
        return finalizeOpenCollectionWarc(
                store, state, RollingCollectionWarcWriter.Stream.POSTS);
    }

    private static List<Path> finalizeOpenCollectionWarcs(
            CollectionStore store,
            CollectionState state) throws IOException {
        var completed = new ArrayList<Path>();
        for (var stream : RollingCollectionWarcWriter.Stream.values()) {
            String open = switch (stream) {
                case POSTS -> state.openWarc;
                case MEDIA -> state.mediaOpenWarc;
                case VIDEO -> state.videoOpenWarc;
            };
            if (open != null) {
                completed.add(finalizeOpenCollectionWarc(
                        store, state, stream));
            }
        }
        return List.copyOf(completed);
    }

    private static Path finalizeOpenCollectionWarc(
            CollectionStore store,
            CollectionState state,
            RollingCollectionWarcWriter.Stream stream) throws IOException {
        String openName = switch (stream) {
            case POSTS -> state.openWarc;
            case MEDIA -> state.mediaOpenWarc;
            case VIDEO -> state.videoOpenWarc;
        };
        String finalName = switch (stream) {
            case POSTS -> state.finalWarc;
            case MEDIA -> state.mediaFinalWarc;
            case VIDEO -> state.videoFinalWarc;
        };
        Path open = store.root().resolve(openName);
        Path completed = store.root().resolve(finalName);
        if (!Files.exists(open)) {
            throw new IOException("Open collection WARC does not exist: " + open);
        }
        try {
            Files.move(open, completed, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(open, completed);
        }
        switch (stream) {
            case POSTS -> {
                state.openWarc = null;
                state.finalWarc = null;
                state.openCommittedOffset = 0;
            }
            case MEDIA -> {
                state.mediaOpenWarc = null;
                state.mediaFinalWarc = null;
                state.mediaCommittedOffset = 0;
            }
            case VIDEO -> {
                state.videoOpenWarc = null;
                state.videoFinalWarc = null;
                state.videoCommittedOffset = 0;
            }
        }
        store.saveCollectionState(state);
        store.log("WARC-COMPLETE " + completed.getFileName());
        return completed;
    }

    /**
     * Keeps API clients and media capture on one logical writer while rolling
     * the collection WARC at complete HTTP exchange boundaries.
     */
    private static final class RollingCollectionWarcWriter extends WarcWriter {
        enum Stream {
            POSTS("warcana-posts-"),
            MEDIA("warcana-media-"),
            VIDEO("warcana-video-");

            final String filenamePrefix;

            Stream(String filenamePrefix) {
                this.filenamePrefix = filenamePrefix;
            }
        }

        private final CollectionStore store;
        private final CollectionState state;
        private final CollectionJob job;
        private final Stream stream;
        private final Function<String, Warcinfo> warcinfoFactory;
        private final ProgressReporter progress;
        private WarcWriter delegate;
        private boolean closed;

        RollingCollectionWarcWriter(
                CollectionStore store,
                CollectionState state,
                CollectionJob job,
                Stream stream,
                Function<String, Warcinfo> warcinfoFactory,
                ProgressReporter progress) throws IOException {
            super(OutputStream.nullOutputStream());
            this.store = store;
            this.state = state;
            this.job = job;
            this.stream = stream;
            this.warcinfoFactory = warcinfoFactory;
            this.progress = progress;
            boolean created = attachOrCreateStream();
            openAtCheckpoint();
            if (created && stream != Stream.POSTS) {
                delegate.write(warcinfoFactory.apply(
                        Path.of(jobFinalWarc()).getFileName().toString()));
                setCommittedOffset(delegate.position());
                store.saveJob(job);
                store.saveCollectionState(state);
            }
        }

        private boolean attachOrCreateStream() throws IOException {
            if (jobOpenWarc() != null) {
                return false;
            }
            String existingOpen = switch (stream) {
                case POSTS -> state.openWarc;
                case MEDIA -> state.mediaOpenWarc;
                case VIDEO -> state.videoOpenWarc;
            };
            if (existingOpen != null) {
                switch (stream) {
                    case POSTS -> {
                        job.openWarc = state.openWarc;
                        job.finalWarc = state.finalWarc;
                        job.committedOffset = state.openCommittedOffset;
                    }
                    case MEDIA -> {
                        job.mediaOpenWarc = state.mediaOpenWarc;
                        job.mediaFinalWarc = state.mediaFinalWarc;
                        job.mediaCommittedOffset = state.mediaCommittedOffset;
                    }
                    case VIDEO -> {
                        job.videoOpenWarc = state.videoOpenWarc;
                        job.videoFinalWarc = state.videoFinalWarc;
                        job.videoCommittedOffset = state.videoCommittedOffset;
                    }
                }
                store.saveJob(job);
                return false;
            }
            String timestamp = DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            String basename = stream.filenamePrefix + timestamp + "-" +
                    UUID.randomUUID().toString().substring(0, 8) + ".warc";
            setPaths(
                    "warcs/" + basename + ".open",
                    "warcs/" + basename);
            setCommittedOffset(0);
            store.saveCollectionState(state);
            store.saveJob(job);
            return true;
        }

        private void openAtCheckpoint() throws IOException {
            Path open = store.root().resolve(jobOpenWarc());
            Files.createDirectories(open.getParent());
            var channel = FileChannel.open(
                    open,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            if (channel.size() < jobCommittedOffset()) {
                channel.close();
                throw new IOException(
                        "Open WARC is shorter than its checkpoint: " + open);
            }
            channel.truncate(jobCommittedOffset());
            channel.position(jobCommittedOffset());
            delegate = new WarcWriter(channel, WarcCompression.NONE);
        }

        private void rollIfNeeded() throws IOException {
            if (delegate.position() < DEFAULT_MAX_WARC_BYTES) {
                return;
            }
            setCommittedOffset(delegate.position());
            store.saveJob(job);
            store.saveCollectionState(state);
            delegate.close();

            Path completed = store.root().resolve(jobFinalWarc());
            Path open = store.root().resolve(jobOpenWarc());
            try {
                Files.move(open, completed, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(open, completed);
            }
            store.log("WARC-ROLLOVER " + completed.getFileName());
            progress.report("Collection WARC reached 1 GiB; starting a new WARC: " +
                    completed.getFileName());

            String timestamp = DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            String basename = stream.filenamePrefix + timestamp + "-" +
                    UUID.randomUUID().toString().substring(0, 8) + ".warc";
            setPaths(
                    "warcs/" + basename + ".open",
                    "warcs/" + basename);
            setCommittedOffset(0);
            store.saveCollectionState(state);
            store.saveJob(job);
            openAtCheckpoint();

            delegate.write(warcinfoFactory.apply(basename));
            setCommittedOffset(delegate.position());
            store.saveJob(job);
            store.saveCollectionState(state);
        }

        @Override
        public synchronized void write(
                org.netpreserve.jwarc.WarcRecord record) throws IOException {
            delegate.write(record);
        }

        @Override
        public org.netpreserve.jwarc.FetchResult fetch(
                URI uri) throws IOException {
            rollIfNeeded();
            return delegate.fetch(uri);
        }

        @Override
        public org.netpreserve.jwarc.FetchResult fetch(
                URI uri,
                org.netpreserve.jwarc.FetchOptions options) throws IOException {
            rollIfNeeded();
            return delegate.fetch(uri, options);
        }

        @Override
        public org.netpreserve.jwarc.FetchResult fetch(
                URI uri,
                org.netpreserve.jwarc.HttpRequest request,
                OutputStream copyTo) throws IOException {
            rollIfNeeded();
            return delegate.fetch(uri, request, copyTo);
        }

        @Override
        public org.netpreserve.jwarc.FetchResult fetch(
                URI uri,
                org.netpreserve.jwarc.HttpRequest request,
                org.netpreserve.jwarc.FetchOptions options) throws IOException {
            rollIfNeeded();
            return delegate.fetch(uri, request, options);
        }

        @Override
        public long position() {
            return delegate == null ? 0 : delegate.position();
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                if (delegate != null) {
                    delegate.close();
                }
                super.close();
            }
        }

        private String jobOpenWarc() {
            return switch (stream) {
                case POSTS -> job.openWarc;
                case MEDIA -> job.mediaOpenWarc;
                case VIDEO -> job.videoOpenWarc;
            };
        }

        private String jobFinalWarc() {
            return switch (stream) {
                case POSTS -> job.finalWarc;
                case MEDIA -> job.mediaFinalWarc;
                case VIDEO -> job.videoFinalWarc;
            };
        }

        private long jobCommittedOffset() {
            return switch (stream) {
                case POSTS -> job.committedOffset;
                case MEDIA -> job.mediaCommittedOffset;
                case VIDEO -> job.videoCommittedOffset;
            };
        }

        private void setPaths(String open, String completed) {
            switch (stream) {
                case POSTS -> {
                    job.openWarc = open;
                    job.finalWarc = completed;
                    state.openWarc = open;
                    state.finalWarc = completed;
                }
                case MEDIA -> {
                    job.mediaOpenWarc = open;
                    job.mediaFinalWarc = completed;
                    state.mediaOpenWarc = open;
                    state.mediaFinalWarc = completed;
                }
                case VIDEO -> {
                    job.videoOpenWarc = open;
                    job.videoFinalWarc = completed;
                    state.videoOpenWarc = open;
                    state.videoFinalWarc = completed;
                }
            }
        }

        private void setCommittedOffset(long offset) {
            switch (stream) {
                case POSTS -> {
                    job.committedOffset = offset;
                    state.openCommittedOffset = offset;
                }
                case MEDIA -> {
                    job.mediaCommittedOffset = offset;
                    state.mediaCommittedOffset = offset;
                }
                case VIDEO -> {
                    job.videoCommittedOffset = offset;
                    state.videoCommittedOffset = offset;
                }
            }
        }
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

    private static Path temporaryOutput(Path output) throws IOException {
        Path absolute = output.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return absolute.resolveSibling(
                ".open-" + UUID.randomUUID() + "-" + absolute.getFileName());
    }

    private static void moveCompletedWarc(
            Path temporary, Path output, boolean replace) throws IOException {
        var options = replace
                ? new StandardCopyOption[]{
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        try {
            Files.move(temporary, output.toAbsolutePath(), options);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(
                    temporary,
                    output.toAbsolutePath(),
                    replace
                            ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                            : new StandardCopyOption[]{});
        }
    }

    private static MastodonAccountArchiver.ArchiveResult archiveMastodon(
            MastodonAccountReference reference, Path output, String warcFilename,
            String userAgent, int pageSize, boolean includeMedia,
            boolean includeVideo, boolean allVideoVariants,
            long maxMediaBytes, long maxTotalMediaBytes,
            ProgressReporter progress,
            ArchiveRunOptions options) throws IOException {
        String accountAddress = reference.account() + "@" +
                URI.create(reference.server()).getRawAuthority();
        Map<String, List<String>> metadata = archiveMetadata(
                userAgent, "mastodon", accountAddress, includeMedia,
                includeVideo, allVideoVariants, maxMediaBytes, maxTotalMediaBytes);

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(warcFilename)
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
                    .archive(reference.account(), options, ArchiveCheckpoint.NONE);
        }
    }

    private static BlueskyAccountArchiver.ArchiveResult archiveBluesky(
            BlueskyAccountReference reference, Path output, String warcFilename,
            String service,
            String userAgent, int pageSize, boolean includeMedia,
            boolean includeVideo, boolean allVideoVariants,
            long maxMediaBytes, long maxTotalMediaBytes,
            ProgressReporter progress,
            ArchiveRunOptions options) throws IOException {
        Map<String, List<String>> metadata = archiveMetadata(
                userAgent, "bluesky", reference.actor(), includeMedia,
                includeVideo, allVideoVariants, maxMediaBytes, maxTotalMediaBytes);
        metadata.put("api", List.of(service));

        try (var warcWriter = new WarcWriter(output)) {
            warcWriter.write(new Warcinfo.Builder()
                    .filename(warcFilename)
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
                    .archive(reference.actor(), options, ArchiveCheckpoint.NONE);
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

    private static void archiveUsage(PrintStream stream) {
        stream.println("Usage: warcana " + ARCHIVE_COMMAND + " [options] ACCOUNT");
        stream.println("       warcana " + ARCHIVE_COMMAND +
                " --collection DIRECTORY [options]");
        stream.println();
        stream.println("Archives a Mastodon or Bluesky account.");
        stream.println("Use --platform when an account handle is ambiguous.");
        stream.println();
        stream.println("Options:");
        stream.println("  -p, --platform NAME    mastodon or bluesky");
        stream.println("  -C, --collection DIR   Archive every entry in DIR/accounts.txt");
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
        stream.println("  --since DATE           Stop before posts older than DATE");
        stream.println("  --until DATE           Skip posts newer than DATE");
        stream.println("  --max-posts NUMBER     Stop after NUMBER selected posts");
        stream.println("  --max-pages NUMBER     Stop after NUMBER API pages");
        stream.println("  -f, --force            Replace an existing output file");
        stream.println("  -q, --quiet            Suppress progress output");
        stream.println("  -h, --help             Show this help");
    }

}
