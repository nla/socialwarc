package org.netpreserve.warcana;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

record ArchiveOptions(
        Path output,
        Path collection,
        String userAgent,
        String service,
        String account,
        Integer pageSize,
        ArchivePlatform requestedPlatform,
        boolean force,
        boolean quiet,
        boolean includeMedia,
        boolean includeVideo,
        boolean allVideoVariants,
        long maxMediaBytes,
        long maxTotalMediaBytes,
        Instant since,
        Instant until,
        int maxPosts,
        int maxPages,
        boolean help) {

    static final String DEFAULT_USER_AGENT = "warcana/0.1.0";
    static final long DEFAULT_MAX_MEDIA_BYTES = 100L * 1024 * 1024;
    static final long DEFAULT_MAX_TOTAL_MEDIA_BYTES = 1024L * 1024 * 1024;

    static ArchiveOptions parse(String[] args) {
        Path output = null;
        Path collection = null;
        String userAgent = DEFAULT_USER_AGENT;
        String service = null;
        String account = null;
        Integer pageSize = null;
        ArchivePlatform requestedPlatform = null;
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
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> {
                    return helpOptions();
                }
                case "-p", "--platform" ->
                        requestedPlatform = ArchivePlatform.parse(
                                requireValue(args, ++i, args[i - 1]));
                case "-o", "--output" ->
                        output = Path.of(requireValue(args, ++i, args[i - 1]));
                case "-C", "--collection" ->
                        collection = Path.of(requireValue(args, ++i, args[i - 1]));
                case "-A", "--user-agent" ->
                        userAgent = requireValue(args, ++i, args[i - 1]);
                case "--service" ->
                        service = requireValue(args, ++i, args[i - 1]);
                case "--page-size" ->
                        pageSize = Integer.parseInt(
                                requireValue(args, ++i, args[i - 1]));
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
                        maxMediaBytes = parseByteSize(
                                requireValue(args, ++i, args[i - 1]));
                case "--max-total-media-bytes" ->
                        maxTotalMediaBytes = parseByteSize(
                                requireValue(args, ++i, args[i - 1]));
                case "--since" ->
                        since = CliSupport.parseDate(
                                requireValue(args, ++i, args[i - 1]), false);
                case "--until" ->
                        until = CliSupport.parseDate(
                                requireValue(args, ++i, args[i - 1]), true);
                case "--max-posts" ->
                        maxPosts = Integer.parseInt(
                                requireValue(args, ++i, args[i - 1]));
                case "--max-pages" ->
                        maxPages = Integer.parseInt(
                                requireValue(args, ++i, args[i - 1]));
                case "-f", "--force" -> force = true;
                case "-q", "--quiet" -> quiet = true;
                default -> {
                    if (args[i].startsWith("-") && !args[i].startsWith("@")) {
                        throw new IllegalArgumentException(
                                "Unknown option: " + args[i]);
                    }
                    if (account != null) {
                        throw new IllegalArgumentException(
                                "Only one account may be archived at a time");
                    }
                    account = args[i];
                }
            }
        }

        var options = new ArchiveOptions(
                output, collection, userAgent, service, account, pageSize,
                requestedPlatform, force, quiet, includeMedia, !excludeVideo,
                allVideoVariants, maxMediaBytes, maxTotalMediaBytes, since,
                until, maxPosts, maxPages, false);
        options.validate();
        return options;
    }

    private static ArchiveOptions helpOptions() {
        return new ArchiveOptions(
                null,
                null,
                DEFAULT_USER_AGENT,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                true,
                false,
                DEFAULT_MAX_MEDIA_BYTES,
                DEFAULT_MAX_TOTAL_MEDIA_BYTES,
                null,
                null,
                0,
                0,
                true);
    }

    ArchivePlatform platformFor(String account) {
        return requestedPlatform == null
                ? ArchivePlatform.detect(account)
                : requestedPlatform;
    }

    private void validate() {
        if (collection == null && account == null) {
            throw new IllegalArgumentException("Missing account");
        }
        if (collection != null && account != null) {
            throw new IllegalArgumentException(
                    "ACCOUNT is read from accounts.txt when --collection is used");
        }
        if (collection != null && output != null) {
            throw new IllegalArgumentException(
                    "--output cannot be used with --collection");
        }
        if (!includeVideo && allVideoVariants) {
            throw new IllegalArgumentException(
                    "--exclude-video and --all-video-variants cannot be used together");
        }
        if (since != null && until != null && since.isAfter(until)) {
            throw new IllegalArgumentException(
                    "--since must not be after --until");
        }
        if (maxPosts < 0 || maxPages < 0) {
            throw new IllegalArgumentException(
                    "--max-posts and --max-pages must not be negative");
        }
    }

    static long parseByteSize(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        long multiplier = 1;
        String number = normalized;
        String[] suffixes = {
                "KIB", "MIB", "GIB", "KB", "MB", "GB", "K", "M", "G"
        };
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix)) {
                number = normalized.substring(
                        0, normalized.length() - suffix.length()).trim();
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
            long bytes = Math.multiplyExact(
                    Long.parseLong(number), multiplier);
            if (bytes < 1) {
                throw new NumberFormatException();
            }
            return bytes;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid byte size: " + value);
        }
    }

    private static String requireValue(
            String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(
                    "Missing value for " + option);
        }
        return args[index];
    }
}
