package org.netpreserve.warcana;

import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.collection.CollectionAccount;
import org.netpreserve.warcana.common.collection.CollectionStore;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class CollectionArchiveRunner {
    private final ArchiveOptions options;
    private final PrintStream out;
    private final PrintStream err;
    private final CollectionStore store;
    private final ProgressReporter progress;

    CollectionArchiveRunner(
            ArchiveOptions options, PrintStream out, PrintStream err) {
        this.options = options;
        this.out = out;
        this.err = err;
        this.store = new CollectionStore(options.collection());
        progress = options.quiet()
                ? ProgressReporter.NONE
                : err::println;
    }

    int run() throws IOException {
        store.initialize();
        List<CollectionAccount> accounts = store.readAccounts();
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Collection has no targets; edit " + store.accountsFile());
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
            try (var lock = acquiredLock) {
                return runLocked(accounts);
            }
        }
    }

    private int runLocked(List<CollectionAccount> accounts)
            throws IOException {
        int succeeded = 0;
        int failed = 0;
        try (var writers = new CollectionRunWriters(
                store, options, progress);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobRunner = new CollectionJobRunner(
                    store, options, out, progress, writers);
            var futures = new java.util.ArrayList<Future<AccountResult>>();
            for (CollectionAccount configured : accounts) {
                ArchivePlatform platform = platform(configured);
                if (isFilteredOut(configured, platform)) {
                    continue;
                }
                if (jobRunner.isDisabled(
                        configured.account(), platform,
                        configured.localTimeline())) {
                    out.println("Skipping disabled target: " +
                            configured.account());
                    continue;
                }
                futures.add(executor.submit(() -> {
                    try {
                        jobRunner.run(
                                configured.account(), platform,
                                configured.localTimeline());
                        return new AccountResult(configured, platform, null);
                    } catch (IOException | RuntimeException e) {
                        try {
                            jobRunner.recordFailure(
                                    configured.account(), platform,
                                    configured.localTimeline(), e);
                        } catch (IOException stateFailure) {
                            e.addSuppressed(stateFailure);
                        }
                        return new AccountResult(configured, platform, e);
                    }
                }));
            }
            for (Future<AccountResult> future : futures) {
                AccountResult result;
                try {
                    result = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                    throw new IOException("Collection archive interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException(
                            "Unable to run collection account job", e.getCause());
                }
                if (result.failure() == null) {
                    succeeded++;
                } else {
                    failed++;
                    reportAccountFailure(
                            result.account().account(), result.platform(),
                            result.failure());
                }
            }
            for (Path completed : writers.finalizeWarcs()) {
                out.println("Completed collection WARC: " + completed);
            }
        }
        out.printf("Collection run complete: %d succeeded, %d failed%n",
                succeeded, failed);
        return failed == 0 ? 0 : 1;
    }

    private ArchivePlatform platform(CollectionAccount account) {
        if (account.localTimeline()) {
            return ArchivePlatform.MASTODON;
        }
        if (account.platform() != null) {
            return ArchivePlatform.parse(account.platform());
        }
        return options.platformFor(account.account());
    }

    private boolean isFilteredOut(
            CollectionAccount account, ArchivePlatform platform) {
        return options.requestedPlatform() != null &&
                account.platform() != null &&
                platform != options.requestedPlatform();
    }

    private void reportAccountFailure(
            String account, ArchivePlatform platform, Throwable failure)
            throws IOException {
        Throwable cause = failure instanceof java.io.UncheckedIOException
                ? failure.getCause()
                : failure;
        String message = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
        err.println("archive failed for " + account + ": " + message);
        store.log("FAILED " + platform.externalName() + " " +
                account + " " + message);
        err.println("Other accounts continued; rerun to resume this target.");
    }

    private record AccountResult(
            CollectionAccount account,
            ArchivePlatform platform,
            Throwable failure) {
    }
}
