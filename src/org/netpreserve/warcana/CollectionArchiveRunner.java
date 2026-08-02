package org.netpreserve.warcana;

import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.collection.CollectionAccount;
import org.netpreserve.warcana.common.collection.CollectionState;
import org.netpreserve.warcana.common.collection.CollectionStore;
import org.netpreserve.warcana.common.collection.CollectionWarcStream;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class CollectionArchiveRunner {
    private final ArchiveOptions options;
    private final PrintStream out;
    private final PrintStream err;
    private final CollectionStore store;
    private final CollectionJobRunner jobRunner;

    CollectionArchiveRunner(
            ArchiveOptions options, PrintStream out, PrintStream err) {
        this.options = options;
        this.out = out;
        this.err = err;
        this.store = new CollectionStore(options.collection());
        ProgressReporter progress = options.quiet()
                ? ProgressReporter.NONE
                : err::println;
        this.jobRunner =
                new CollectionJobRunner(store, options, out, progress);
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
                return runLocked(orderForResume(accounts));
            }
        }
    }

    private List<CollectionAccount> orderForResume(
            List<CollectionAccount> accounts) {
        var ordered = new ArrayList<>(accounts);
        for (int i = 0; i < ordered.size(); i++) {
            CollectionAccount candidate = ordered.get(i);
            ArchivePlatform platform = platform(candidate);
            String key = CollectionStore.accountKey(
                    candidate.localTimeline()
                            ? "mastodon-local"
                            : platform.externalName(),
                    candidate.account());
            if (Files.exists(store.jobFile(key))) {
                if (i > 0) {
                    ordered.remove(i);
                    ordered.add(0, candidate);
                }
                break;
            }
        }
        return ordered;
    }

    private int runLocked(List<CollectionAccount> accounts)
            throws IOException {
        int succeeded = 0;
        int failed = 0;
        for (int accountIndex = 0;
             accountIndex < accounts.size();
             accountIndex++) {
            CollectionAccount configured = accounts.get(accountIndex);
            ArchivePlatform platform = platform(configured);
            if (isFilteredOut(configured, platform)) {
                continue;
            }
            try {
                jobRunner.run(
                        configured.account(), platform,
                        configured.localTimeline());
                succeeded++;
            } catch (IOException | RuntimeException e) {
                failed++;
                reportAccountFailure(configured.account(), platform, e);
                break;
            }
            rollPostsBetweenAccountsIfNeeded(
                    accountIndex, accounts.size());
        }
        if (failed == 0) {
            for (Path completed :
                    finalizeOpenWarcs(store.loadCollectionState())) {
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
        err.println("The shared WARC remains open; rerun this command " +
                "to resume before later accounts are archived.");
    }

    private void rollPostsBetweenAccountsIfNeeded(
            int accountIndex, int accountCount) throws IOException {
        CollectionState state = store.loadCollectionState();
        if (state.posts.openWarc() != null &&
                state.posts.committedOffset() >=
                        RollingCollectionWarcWriter.MAX_WARC_BYTES &&
                accountIndex + 1 < accountCount) {
            Path rolled = finalizeOpenWarc(
                    state, CollectionWarcStream.POSTS);
            out.println(
                    "Closed collection WARC at size limit: " + rolled);
        }
    }

    private List<Path> finalizeOpenWarcs(CollectionState state)
            throws IOException {
        var completed = new ArrayList<Path>();
        for (var stream : CollectionWarcStream.values()) {
            if (stream.from(state).openWarc() != null) {
                completed.add(finalizeOpenWarc(state, stream));
            }
        }
        return List.copyOf(completed);
    }

    private Path finalizeOpenWarc(
            CollectionState state,
            CollectionWarcStream stream)
            throws IOException {
        var streamState = stream.from(state);
        Path open = store.root().resolve(streamState.openWarc());
        Path completed = store.root().resolve(streamState.finalWarc());
        if (!Files.exists(open)) {
            throw new IOException(
                    "Open collection WARC does not exist: " + open);
        }
        RollingCollectionWarcWriter.moveCompleted(open, completed);
        streamState.clear();
        store.saveCollectionState(state);
        store.log("WARC-COMPLETE " + completed.getFileName());
        return completed;
    }

}
