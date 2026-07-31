package org.netpreserve.warcana.common;

import org.netpreserve.jwarc.FetchOptions;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.WarcWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Captures discovered media URLs into a WARC with deduplication and byte limits.
 */
public class MediaCapturer implements MediaSink {
    private static final Logger log = LoggerFactory.getLogger(MediaCapturer.class);
    private static final long MAX_PLAYLIST_BYTES = 2L * 1024 * 1024;
    private static final ScheduledExecutorService HEARTBEATS =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "warcana-media-progress");
                thread.setDaemon(true);
                return thread;
            });

    private final WarcWriter writer;
    private final String userAgent;
    private final long maxResourceBytes;
    private final long maxTotalBytes;
    private final ProgressReporter progress;
    private final boolean allVideoVariants;
    private final Consumer<String> resourceCompleted;
    private final Set<URI> seen = new HashSet<>();
    private int captured;
    private int failed;
    private int skipped;
    private long bytes;

    public MediaCapturer(WarcWriter writer, String userAgent,
                         long maxResourceBytes, long maxTotalBytes) {
        this(writer, userAgent, maxResourceBytes, maxTotalBytes,
                false, ProgressReporter.NONE);
    }

    public MediaCapturer(WarcWriter writer, String userAgent,
                         long maxResourceBytes, long maxTotalBytes,
                         ProgressReporter progress) {
        this(writer, userAgent, maxResourceBytes, maxTotalBytes, false, progress);
    }

    public MediaCapturer(WarcWriter writer, String userAgent,
                         long maxResourceBytes, long maxTotalBytes,
                         boolean allVideoVariants, ProgressReporter progress) {
        this(writer, userAgent, maxResourceBytes, maxTotalBytes,
                allVideoVariants, progress,
                new MediaCaptureStats(0, 0, 0, 0), List.of(), ignored -> {
                });
    }

    public MediaCapturer(WarcWriter writer, String userAgent,
                         long maxResourceBytes, long maxTotalBytes,
                         boolean allVideoVariants, ProgressReporter progress,
                         MediaCaptureStats initialStats,
                         Collection<String> previouslySeen) {
        this(writer, userAgent, maxResourceBytes, maxTotalBytes,
                allVideoVariants, progress, initialStats, previouslySeen,
                ignored -> {
                });
    }

    public MediaCapturer(WarcWriter writer, String userAgent,
                         long maxResourceBytes, long maxTotalBytes,
                         boolean allVideoVariants, ProgressReporter progress,
                         MediaCaptureStats initialStats,
                         Collection<String> previouslySeen,
                         Consumer<String> resourceCompleted) {
        if (maxResourceBytes < 1 || maxTotalBytes < 1) {
            throw new IllegalArgumentException("Media byte limits must be positive");
        }
        this.writer = writer;
        this.userAgent = userAgent;
        this.maxResourceBytes = maxResourceBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.allVideoVariants = allVideoVariants;
        this.progress = progress;
        this.resourceCompleted = resourceCompleted;
        this.captured = initialStats.captured();
        this.failed = initialStats.failed();
        this.skipped = initialStats.skipped();
        this.bytes = initialStats.bytes();
        for (String value : previouslySeen) {
            try {
                URI uri = URI.create(value).normalize();
                if (isSafeHttpUri(uri)) {
                    seen.add(uri);
                }
            } catch (IllegalArgumentException | IOException ignored) {
                // Ignore invalid persisted values; they will remain skipped.
            }
        }
    }

    @Override
    public void captureAll(Collection<String> urls) {
        for (String url : urls) {
            captureOne(url);
        }
    }

    public void captureOne(String value) {
        capture(value);
    }

    public Set<String> seenUrls() {
        return seen.stream()
                .map(URI::toString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void capture(String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        URI uri;
        try {
            uri = URI.create(value);
            if (!isSafeHttpUri(uri)) {
                skipped++;
                log.warn("Skipping unsafe media URL {}", uri);
                return;
            }
        } catch (IllegalArgumentException | IOException e) {
            skipped++;
            log.warn("Skipping invalid media URL {}", value);
            return;
        }

        uri = uri.normalize();
        if (!seen.add(uri)) {
            return;
        }
        long remaining = maxTotalBytes - bytes;
        if (remaining <= 0) {
            skipped++;
            return;
        }

        boolean playlist = isPlaylist(uri);
        long limit = Math.min(maxResourceBytes, remaining);
        if (playlist) {
            limit = Math.min(limit, MAX_PLAYLIST_BYTES);
        }
        ByteArrayOutputStream copy = playlist ? new ByteArrayOutputStream() : null;
        FetchOptions options = new FetchOptions()
                .userAgent(userAgent)
                .readTimeout(60_000)
                .maxTime(5 * 60_000)
                .maxLength(limit);
        if (copy != null) {
            options.copyTo(copy);
        }

        String display = displayUri(uri);
        progress.report("Capturing media: " + display);
        long started = System.nanoTime();
        ScheduledFuture<?> heartbeat = progress == ProgressReporter.NONE
                ? null
                : HEARTBEATS.scheduleAtFixedRate(
                        () -> progress.report("Still capturing media (" +
                                TimeUnit.NANOSECONDS.toSeconds(
                                        System.nanoTime() - started) +
                                "s elapsed): " + display),
                        10, 10, TimeUnit.SECONDS);
        try {
            var result = writer.fetch(uri, options);
            if (heartbeat != null) {
                heartbeat.cancel(false);
                heartbeat = null;
            }
            long responseBytes = Math.min(result.response().body().size(), limit);
            bytes += responseBytes;
            HttpResponse response = result.response().http();
            if (result.exception() != null) {
                failed++;
                return;
            }
            if (response.status() >= 300 && response.status() < 400) {
                var location = response.headers().first("Location");
                if (location.isPresent()) {
                    progress.report("Following media redirect: " + display);
                    capture(uri.resolve(location.get()).toString());
                } else {
                    failed++;
                    progress.report("Media capture failed (redirect without Location): " +
                            display);
                }
                return;
            }
            if (response.status() < 200 || response.status() >= 300) {
                failed++;
                progress.report("Media capture failed (HTTP " + response.status() + "): " +
                        display);
                return;
            }
            captured++;
            progress.report("Captured media #" + captured + " (" +
                    formatBytes(responseBytes) + ", " + formatBytes(bytes) +
                    " total): " + display);
            if (playlist && copy != null) {
                capturePlaylistReferences(uri, copy.toByteArray());
            }
        } catch (UncheckedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            failed++;
            progress.report("Media capture failed: " + display +
                    " (" + e.getClass().getSimpleName() + ")");
            log.warn("Failed to capture media {}", uri, e);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            resourceCompleted.accept(uri.toString());
        }
    }

    private void capturePlaylistReferences(URI playlist, byte[] rawResponse) {
        try {
            HttpResponse response = HttpResponse.parse(Channels.newChannel(
                    new ByteArrayInputStream(rawResponse)));
            String content = new String(
                    response.bodyDecoded().stream().readAllBytes(),
                    StandardCharsets.UTF_8);
            PlaylistPlan plan = planPlaylist(playlist, content, allVideoVariants);
            if (plan.selectedVariant() != null) {
                progress.report("Selected highest-quality HLS variant (" +
                        plan.selectedVariant().qualityDescription() + "): " +
                        displayUri(plan.selectedVariant().uri()));
            }
            for (URI reference : plan.references()) {
                capture(reference.toString());
            }
        } catch (UncheckedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            failed++;
            log.warn("Failed to parse media playlist {}", playlist, e);
        }
    }

    static PlaylistPlan planPlaylist(
            URI playlist, String content, boolean allVideoVariants) {
        List<String> lines = content.lines().map(String::trim).toList();
        List<HlsVariant> variants = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }
            String variantUri = nextUriLine(lines, i + 1);
            if (variantUri == null) {
                continue;
            }
            URI resolved = resolve(playlist, variantUri);
            if (resolved != null) {
                variants.add(new HlsVariant(
                        resolved,
                        longAttribute(line, "BANDWIDTH"),
                        longAttribute(line, "AVERAGE-BANDWIDTH"),
                        attribute(line, "RESOLUTION"),
                        attribute(line, "AUDIO")));
            }
        }

        var references = new LinkedHashSet<URI>();
        if (!variants.isEmpty()) {
            if (allVideoVariants) {
                variants.stream().map(HlsVariant::uri).forEach(references::add);
                for (String line : lines) {
                    if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                            line.startsWith("#EXT-X-MEDIA:") ||
                            line.startsWith("#EXT-X-SESSION-KEY:")) {
                        addUriAttribute(playlist, line, references);
                    }
                }
                return new PlaylistPlan(List.copyOf(references), null);
            }

            HlsVariant selected = variants.stream()
                    .max(Comparator
                            .comparingLong(HlsVariant::effectiveBandwidth)
                            .thenComparingLong(HlsVariant::resolutionPixels))
                    .orElseThrow();
            references.add(selected.uri());
            addDefaultAudioRendition(
                    playlist, lines, selected.audioGroup(), references);
            for (String line : lines) {
                if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                    addUriAttribute(playlist, line, references);
                }
            }
            return new PlaylistPlan(List.copyOf(references), selected);
        }

        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("#")) {
                URI resolved = resolve(playlist, line);
                if (resolved != null) {
                    references.add(resolved);
                }
            } else if (line.startsWith("#EXT-X-KEY:") ||
                    line.startsWith("#EXT-X-MAP:")) {
                addUriAttribute(playlist, line, references);
            }
        }
        return new PlaylistPlan(List.copyOf(references), null);
    }

    private static void addDefaultAudioRendition(
            URI playlist, List<String> lines, String audioGroup,
            LinkedHashSet<URI> references) {
        if (audioGroup == null) {
            return;
        }
        lines.stream()
                .filter(line -> line.startsWith("#EXT-X-MEDIA:"))
                .filter(line -> "AUDIO".equalsIgnoreCase(attribute(line, "TYPE")))
                .filter(line -> audioGroup.equals(attribute(line, "GROUP-ID")))
                .filter(line -> attribute(line, "URI") != null)
                .max(Comparator
                        .comparing((String line) ->
                                "YES".equalsIgnoreCase(attribute(line, "DEFAULT")))
                        .thenComparing(line ->
                                "YES".equalsIgnoreCase(attribute(line, "AUTOSELECT"))))
                .ifPresent(line -> addUriAttribute(playlist, line, references));
    }

    private static String nextUriLine(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            return line.startsWith("#") ? null : line;
        }
        return null;
    }

    private static void addUriAttribute(
            URI playlist, String line, LinkedHashSet<URI> references) {
        String value = attribute(line, "URI");
        URI resolved = value == null ? null : resolve(playlist, value);
        if (resolved != null) {
            references.add(resolved);
        }
    }

    private static URI resolve(URI playlist, String value) {
        try {
            return playlist.resolve(value).normalize();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String attribute(String line, String name) {
        Pattern pattern = Pattern.compile(
                "(?:^|,)" + Pattern.quote(name) + "=(?:\"([^\"]*)\"|([^,]*))",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(line.substring(line.indexOf(':') + 1));
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) == null ? matcher.group(2).trim() : matcher.group(1);
    }

    private static long longAttribute(String line, String name) {
        try {
            String value = attribute(line, name);
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isPlaylist(URI uri) {
        String path = uri.getPath();
        return path != null && path.toLowerCase().endsWith(".m3u8");
    }

    private static String displayUri(URI uri) {
        String display;
        try {
            display = new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    null,
                    null).toString();
        } catch (Exception e) {
            display = uri.getScheme() + "://" + uri.getHost();
        }
        return display.length() <= 160 ? display : display.substring(0, 157) + "...";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static boolean isSafeHttpUri(URI uri) throws IOException {
        if (uri.getHost() == null ||
                (!"http".equalsIgnoreCase(uri.getScheme()) &&
                        !"https".equalsIgnoreCase(uri.getScheme()))) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] rawAddress = address.getAddress();
            boolean uniqueLocalIpv6 = rawAddress.length == 16 &&
                    (rawAddress[0] & 0xfe) == 0xfc;
            if (address.isAnyLocalAddress() ||
                    address.isLoopbackAddress() ||
                    address.isLinkLocalAddress() ||
                    address.isSiteLocalAddress() ||
                    address.isMulticastAddress() ||
                    uniqueLocalIpv6) {
                return false;
            }
        }
        return true;
    }

    @Override
    public MediaCaptureStats stats() {
        return new MediaCaptureStats(captured, failed, skipped, bytes);
    }

    record PlaylistPlan(List<URI> references, HlsVariant selectedVariant) {
    }

    record HlsVariant(
            URI uri,
            long bandwidth,
            long averageBandwidth,
            String resolution,
            String audioGroup) {

        long effectiveBandwidth() {
            return bandwidth > 0 ? bandwidth : averageBandwidth;
        }

        long resolutionPixels() {
            if (resolution == null) {
                return 0;
            }
            try {
                String[] dimensions = resolution.toLowerCase().split("x", 2);
                return Math.multiplyExact(
                        Long.parseLong(dimensions[0]),
                        Long.parseLong(dimensions[1]));
            } catch (ArithmeticException | NumberFormatException e) {
                return 0;
            }
        }

        String qualityDescription() {
            var parts = new ArrayList<String>();
            if (effectiveBandwidth() > 0) {
                parts.add(String.format(
                        java.util.Locale.ROOT,
                        "%.1f Mbps",
                        effectiveBandwidth() / 1_000_000.0));
            }
            if (resolution != null && !resolution.isBlank()) {
                parts.add(resolution);
            }
            return parts.isEmpty() ? "highest advertised quality" : String.join(", ", parts);
        }
    }
}
