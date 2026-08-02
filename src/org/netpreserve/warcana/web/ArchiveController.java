package org.netpreserve.warcana.web;

import org.netpreserve.warcana.ArchiveCommand;
import org.netpreserve.warcana.common.collection.CollectionStore;

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
import java.util.List;
import java.util.Properties;
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

    record Settings(boolean enabled, int intervalHours,
                    boolean includeMedia, boolean includeVideo) {
    }

    record Status(boolean running, Instant startedAt, Instant finishedAt,
                  Integer exitCode, Instant nextRun, Settings settings,
                  List<String> output) {
    }

    private final CollectionStore store;
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final ArrayDeque<String> output = new ArrayDeque<>();
    private Settings settings;
    private Future<?> run;
    private Thread runThread;
    private boolean running;
    private ScheduledFuture<?> scheduled;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant nextRun;
    private Integer exitCode;

    ArchiveController(CollectionStore store) throws IOException {
        this.store = store;
        settings = loadSettings();
        reschedule();
    }

    synchronized boolean start() {
        if (running) {
            return false;
        }
        running = true;
        startedAt = Instant.now();
        finishedAt = null;
        exitCode = null;
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
                exitCode, nextRun, settings, List.copyOf(output));
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
            List<String> arguments = new ArrayList<>(List.of(
                    "archive", "--collection", store.root().toString()));
            Settings current;
            synchronized (this) {
                current = settings;
            }
            if (current.includeMedia()) {
                arguments.add("--include-media");
                if (!current.includeVideo()) {
                    arguments.add("--exclude-video");
                }
            }
            result = ArchiveCommand.run(arguments.toArray(String[]::new), sink, sink);
        } catch (RuntimeException e) {
            append("Capture failed: " + e.getMessage());
            result = 1;
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
        properties.setProperty("enabled", Boolean.toString(settings.enabled()));
        properties.setProperty("intervalHours", Integer.toString(settings.intervalHours()));
        properties.setProperty("includeMedia", Boolean.toString(settings.includeMedia()));
        properties.setProperty("includeVideo", Boolean.toString(settings.includeVideo()));
        Files.createDirectories(store.stateDirectory());
        var temporary = settingsFile().resolveSibling("archive-schedule.properties.tmp");
        try (var stream = Files.newOutputStream(
                temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(stream, "Warcana archive schedule");
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
