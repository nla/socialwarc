package org.netpreserve.socialwarc.web;

import org.netpreserve.socialwarc.ArchiveCommand;
import org.netpreserve.socialwarc.bamboo.BambooConfiguration;
import org.netpreserve.socialwarc.bamboo.BambooIngestWorker;
import org.netpreserve.socialwarc.bamboo.BambooOutboxItem;
import org.netpreserve.socialwarc.common.collection.CollectionStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs and schedules collection archives for the local management UI. */
final class ArchiveController implements AutoCloseable {
    private static final int MAX_LOG_LINES = 300;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    record Settings(String userAgent, boolean enabled, int intervalHours,
                    boolean includeMedia, boolean includeVideo) {
    }

    record Status(boolean running, Instant startedAt, Instant finishedAt,
                  Integer exitCode, Instant nextRun, Settings settings,
                  List<String> output, Progress progress, boolean bambooEnabled,
                  List<BambooBatchStatus> bambooBatches) {
    }

    record Progress(
            int targets, int targetsCompleted, int targetsFailed,
            int targetsActive, int targetsQueued, int posts, int pages,
            int mediaCaptured, int mediaFailed, int mediaSkipped,
            int mediaQueued, int videoCaptured, int videoFailed,
            int videoSkipped, int videoQueued, long bytes) {
    }

    record BambooBatchStatus(
            String id, String status, int uploaded, int total,
            int attempts, String lastError, String nextAttemptAt) {
    }

    private final CollectionStore store;
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final ArrayDeque<String> output = new ArrayDeque<>();
    private final Map<String, ArchiveCommand.TargetProgress> targetProgress =
            new HashMap<>();
    private final Set<String> completedTargets = new HashSet<>();
    private final Set<String> failedTargets = new HashSet<>();
    private final ArchiveCommand.ProgressListener progressListener =
            new ArchiveCommand.ProgressListener() {
                @Override
                public void targetsScheduled(int targets) {
                    synchronized (ArchiveController.this) {
                        scheduledTargets = targets;
                    }
                }

                @Override
                public void targetUpdated(
                        ArchiveCommand.TargetProgress progress) {
                    synchronized (ArchiveController.this) {
                        targetProgress.put(progress.key(), progress);
                    }
                }

                @Override
                public void targetCompleted(
                        ArchiveCommand.TargetProgress progress) {
                    synchronized (ArchiveController.this) {
                        targetProgress.put(progress.key(), progress);
                        failedTargets.remove(progress.key());
                        completedTargets.add(progress.key());
                    }
                }

                @Override
                public void targetFailed(String key) {
                    synchronized (ArchiveController.this) {
                        completedTargets.remove(key);
                        failedTargets.add(key);
                    }
                }
            };
    private final BambooIngestWorker bambooWorker;
    private Settings settings;
    private Future<?> run;
    private Thread runThread;
    private boolean running;
    private ScheduledFuture<?> scheduled;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant nextRun;
    private Integer exitCode;
    private int scheduledTargets;

    ArchiveController(CollectionStore store) throws IOException {
        this(store, null);
    }

    ArchiveController(
            CollectionStore store, BambooConfiguration bambooConfiguration)
            throws IOException {
        this.store = store;
        bambooWorker = bambooConfiguration == null ||
                !bambooConfiguration.ingestEnabled()
                ? null
                : new BambooIngestWorker(store, bambooConfiguration);
        settings = loadSettings();
        reschedule();
        if (bambooWorker != null) {
            BambooOutboxItem queued = bambooWorker.outbox()
                    .enqueueMissing(store.completedWarcs());
            if (queued != null) {
                append("Queued " + queued.files.size() +
                        " existing WARC files for Bamboo ingest");
            }
            bambooWorker.start();
        }
    }

    synchronized boolean start() {
        if (running) {
            return false;
        }
        running = true;
        startedAt = Instant.now();
        finishedAt = null;
        exitCode = null;
        targetProgress.clear();
        completedTargets.clear();
        failedTargets.clear();
        scheduledTargets = 0;
        append("Starting capture");
        run = runner.submit(this::execute);
        return true;
    }

    synchronized boolean stop() {
        if (!running || run == null) {
            return false;
        }
        append("Stop requested");
        if (runThread == null) {
            run.cancel(false);
            running = false;
            finishedAt = Instant.now();
            exitCode = 1;
        } else {
            runThread.interrupt();
        }
        return true;
    }

    synchronized void update(Settings value) throws IOException {
        if (value.intervalHours() < 1 || value.intervalHours() > 8760) {
            throw new IllegalArgumentException(
                    "Schedule interval must be between 1 and 8760 hours");
        }
        settings = value;
        saveSettings();
        reschedule();
    }

    synchronized Status status() {
        return new Status(
                running, startedAt, finishedAt,
                exitCode, nextRun, settings, List.copyOf(output),
                progress(), bambooWorker != null, bambooStatus());
    }

    private List<BambooBatchStatus> bambooStatus() {
        if (bambooWorker == null) {
            return List.of();
        }
        try {
            return bambooWorker.outbox().pending().stream()
                    .map(item -> new BambooBatchStatus(
                            item.id, item.status,
                            (int) item.files.stream()
                                    .filter(file -> file.uploaded).count(),
                            item.files.size(), item.attempts,
                            item.lastError, item.nextAttemptAt))
                    .toList();
        } catch (IOException e) {
            return List.of(new BambooBatchStatus(
                    "outbox", "unreadable", 0, 0, 0,
                    e.getMessage(), null));
        }
    }

    private Progress progress() {
        int active = (int) targetProgress.keySet().stream()
                .filter(key -> !completedTargets.contains(key) &&
                        !failedTargets.contains(key))
                .count();
        int finished = completedTargets.size() + failedTargets.size();
        int queued = Math.max(0, scheduledTargets - finished - active);
        int posts = 0;
        int pages = 0;
        int mediaCaptured = 0;
        int mediaFailed = 0;
        int mediaSkipped = 0;
        int mediaQueued = 0;
        int videoCaptured = 0;
        int videoFailed = 0;
        int videoSkipped = 0;
        int videoQueued = 0;
        long bytes = 0;
        for (ArchiveCommand.TargetProgress target : targetProgress.values()) {
            posts += target.posts();
            pages += target.pages();
            mediaCaptured += target.mediaCaptured();
            mediaFailed += target.mediaFailed();
            mediaSkipped += target.mediaSkipped();
            mediaQueued += target.mediaQueued();
            videoCaptured += target.videoCaptured();
            videoFailed += target.videoFailed();
            videoSkipped += target.videoSkipped();
            videoQueued += target.videoQueued();
            bytes += target.mediaBytes() + target.videoBytes();
        }
        return new Progress(
                scheduledTargets, completedTargets.size(), failedTargets.size(),
                active, queued, posts, pages,
                mediaCaptured, mediaFailed, mediaSkipped, mediaQueued,
                videoCaptured, videoFailed, videoSkipped, videoQueued, bytes);
    }

    private void execute() {
        synchronized (this) {
            if (!running) {
                return;
            }
            runThread = Thread.currentThread();
        }
        var sink = new PrintStream(lineOutput(), true, StandardCharsets.UTF_8);
        int result;
        try (sink) {
            Settings current;
            synchronized (this) {
                current = settings;
            }
            List<String> arguments = new ArrayList<>(List.of(
                    "archive", "--collection", store.root().toString(),
                    "--user-agent", current.userAgent()));
            if (current.includeMedia()) {
                arguments.add("--include-media");
                if (!current.includeVideo()) {
                    arguments.add("--exclude-video");
                }
            }
            result = ArchiveCommand.run(
                    arguments.toArray(String[]::new), sink, sink,
                    progressListener);
        } catch (RuntimeException e) {
            append("Capture failed: " + e.getMessage());
            result = 1;
        }
        if (bambooWorker != null) {
            try {
                var item = bambooWorker.outbox()
                        .enqueueMissing(store.completedWarcs());
                if (item != null) {
                    append("Queued " + item.files.size() +
                            " WARC files for Bamboo ingest");
                    bambooWorker.trigger();
                }
            } catch (IOException e) {
                append("Unable to queue Bamboo ingest: " + e.getMessage());
                result = 1;
            }
        }
        synchronized (this) {
            exitCode = result;
            finishedAt = Instant.now();
            running = false;
            runThread = null;
            append(result == 0 ? "Capture complete" :
                    "Capture stopped or failed (exit " + result + ")");
        }
    }

    private synchronized void reschedule() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        nextRun = null;
        if (!settings.enabled()) {
            return;
        }
        long hours = settings.intervalHours();
        nextRun = Instant.now().plus(Duration.ofHours(hours));
        scheduled = scheduler.scheduleWithFixedDelay(() -> {
            start();
            synchronized (this) {
                nextRun = Instant.now().plus(Duration.ofHours(hours));
            }
        }, hours, hours, TimeUnit.HOURS);
    }

    private Settings loadSettings() throws IOException {
        var properties = new Properties();
        if (Files.isRegularFile(settingsFile())) {
            try (var input = Files.newInputStream(settingsFile())) {
                properties.load(input);
            }
        }
        return new Settings(
                properties.getProperty(
                        "userAgent", ArchiveCommand.DEFAULT_USER_AGENT),
                Boolean.parseBoolean(properties.getProperty("enabled", "false")),
                parseInterval(properties.getProperty("intervalHours", "24")),
                Boolean.parseBoolean(properties.getProperty("includeMedia", "true")),
                Boolean.parseBoolean(properties.getProperty("includeVideo", "true")));
    }

    private int parseInterval(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= 8760 ? parsed : 24;
        } catch (NumberFormatException e) {
            return 24;
        }
    }

    private void saveSettings() throws IOException {
        var properties = new Properties();
        properties.setProperty("userAgent", settings.userAgent());
        properties.setProperty("enabled", Boolean.toString(settings.enabled()));
        properties.setProperty("intervalHours", Integer.toString(settings.intervalHours()));
        properties.setProperty("includeMedia", Boolean.toString(settings.includeMedia()));
        properties.setProperty("includeVideo", Boolean.toString(settings.includeVideo()));
        Files.createDirectories(store.stateDirectory());
        var temporary = settingsFile().resolveSibling("archive-schedule.properties.tmp");
        try (var stream = Files.newOutputStream(
                temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(stream, "SocialWARC archive schedule");
        }
        try {
            Files.move(temporary, settingsFile(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, settingsFile(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private java.nio.file.Path settingsFile() {
        return store.stateDirectory().resolve("archive-schedule.properties");
    }

    private synchronized void append(String line) {
        for (String part : line.split("\\R")) {
            if (!part.isBlank()) {
                output.addLast(LOG_TIME.format(Instant.now()) + "  " + part);
                while (output.size() > MAX_LOG_LINES) {
                    output.removeFirst();
                }
            }
        }
    }

    OutputStream lineOutput() {
        return new LineOutput();
    }

    @Override
    public synchronized void close() {
        if (running && run != null) {
            if (runThread == null) {
                run.cancel(false);
            } else {
                runThread.interrupt();
            }
        }
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        runner.shutdownNow();
        scheduler.shutdownNow();
        if (bambooWorker != null) {
            bambooWorker.close();
        }
    }

    private final class LineOutput extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void write(int value) {
            if (value == '\n') {
                emitBuffer();
            } else if (value != '\r') {
                buffer.write(value);
            }
        }

        @Override
        public void flush() {
            // PrintStream flushes during formatted output, including between
            // individual arguments. Only a newline completes a log line.
        }

        @Override
        public void close() {
            emitBuffer();
        }

        private void emitBuffer() {
            if (buffer.size() > 0) {
                append(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            }
        }
    }
}
