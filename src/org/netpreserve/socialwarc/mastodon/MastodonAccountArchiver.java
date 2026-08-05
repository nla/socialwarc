package org.netpreserve.socialwarc.mastodon;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.socialwarc.common.MediaCaptureStats;
import org.netpreserve.socialwarc.common.MediaSink;
import org.netpreserve.socialwarc.common.ProgressReporter;
import org.netpreserve.socialwarc.common.ArchiveCheckpoint;
import org.netpreserve.socialwarc.common.ArchiveRunOptions;
import org.netpreserve.socialwarc.mastodon.model.Account;
import org.netpreserve.socialwarc.mastodon.model.Status;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Archives the account lookup and every page of public statuses returned by a Mastodon server.
 */
public class MastodonAccountArchiver {
    private final MastodonApi client;
    private final int pageSize;
    private final MediaSink mediaSink;
    private final ProgressReporter progress;
    private final boolean includeVideo;

    public MastodonAccountArchiver(
            String server,
            String userAgent,
            WarcWriter warcWriter,
            int pageSize,
            MediaSink mediaSink,
            ProgressReporter progress,
            boolean includeVideo) {
        if (pageSize < 1 || pageSize > 40) {
            throw new IllegalArgumentException("Page size must be between 1 and 40");
        }
        this.client = new MastodonClient(server, userAgent, warcWriter);
        this.pageSize = pageSize;
        this.mediaSink = mediaSink;
        this.progress = progress;
        this.includeVideo = includeVideo;
    }

    MastodonAccountArchiver(
            MastodonApi client,
            int pageSize,
            MediaSink mediaSink,
            ProgressReporter progress,
            boolean includeVideo) {
        if (pageSize < 1 || pageSize > 40) {
            throw new IllegalArgumentException("Page size must be between 1 and 40");
        }
        this.client = client;
        this.pageSize = pageSize;
        this.mediaSink = mediaSink;
        this.progress = progress;
        this.includeVideo = includeVideo;
    }

    public ArchiveResult archive(String accountName) throws IOException {
        return archive(
                accountName,
                ArchiveRunOptions.unbounded(),
                ArchiveCheckpoint.NONE);
    }

    public ArchiveResult archive(
            String accountName,
            ArchiveRunOptions options,
            ArchiveCheckpoint checkpoint) throws IOException {
        progress.report("Looking up Mastodon account: " + accountName);
        Account account = client.lookupAccount(accountName);
        progress.report("Found Mastodon account: " + account.acct());
        if (mediaSink != null) {
            mediaSink.captureAll(account.mediaUrls());
        }
        String maxId = options.cursor();
        int pages = options.pagesAlreadyFetched();
        int statusesArchived = options.postsAlreadyArchived();
        String newestIdentity = options.newestIdentity();
        boolean complete = false;
        Set<String> cursors = new HashSet<>();

        while (true) {
            if (options.pageLimitReached(pages) ||
                    options.postLimitReached(statusesArchived)) {
                break;
            }
            progress.report("Fetching Mastodon statuses page " + (pages + 1) + "...");
            var statuses = client.getAccountStatuses(account.id(), null, maxId, pageSize);
            pages++;
            var selected = new java.util.ArrayList<Status>();
            boolean reachedExisting = false;
            boolean reachedSince = false;
            for (Status status : statuses) {
                if (options.stopIdentity() != null &&
                        options.stopIdentity().equals(status.id())) {
                    reachedExisting = true;
                    break;
                }
                if (options.since() != null &&
                        status.createdAt().isBefore(options.since())) {
                    reachedSince = true;
                    break;
                }
                if (options.until() != null &&
                        status.createdAt().isAfter(options.until())) {
                    continue;
                }
                if (options.postLimitReached(statusesArchived + selected.size())) {
                    break;
                }
                if (newestIdentity == null) {
                    newestIdentity = status.id();
                }
                selected.add(status);
            }
            statusesArchived += selected.size();
            if (selected.size() == statuses.size()) {
                progress.report("Fetched " + statuses.size() +
                        (statuses.size() == 1 ? " status (" : " statuses (") +
                        statusesArchived + " total)");
            } else {
                progress.report("Selected " + selected.size() + " of " + statuses.size() +
                        (statuses.size() == 1 ? " fetched status (" : " fetched statuses (") +
                        statusesArchived + " total)");
            }
            if (mediaSink != null) {
                var mediaUrls = new java.util.ArrayList<String>();
                selected.forEach(status ->
                        mediaUrls.addAll(status.mediaUrls(false)));
                mediaSink.discover(mediaUrls);
                if (includeVideo) {
                    var videoUrls = new java.util.ArrayList<String>();
                    selected.forEach(status ->
                            videoUrls.addAll(status.videoUrls()));
                    mediaSink.discoverVideo(videoUrls);
                }
            }
            if (statuses.isEmpty()) {
                complete = true;
                checkpoint.save(new ArchiveCheckpoint.PageCheckpoint(
                        null, newestIdentity, statusesArchived, pages, true));
                break;
            }

            String nextMaxId = statuses.get(statuses.size() - 1).id();
            boolean bounded = reachedExisting || reachedSince ||
                    options.postLimitReached(statusesArchived) ||
                    options.pageLimitReached(pages);
            if (reachedExisting || reachedSince) {
                complete = true;
            }
            checkpoint.save(new ArchiveCheckpoint.PageCheckpoint(
                    bounded ? null : nextMaxId,
                    newestIdentity,
                    statusesArchived,
                    pages,
                    complete));
            if (mediaSink != null) {
                mediaSink.drain();
            }
            if (bounded) {
                break;
            }
            if (!cursors.add(nextMaxId)) {
                throw new IOException("Mastodon pagination repeated status ID " + nextMaxId);
            }
            maxId = nextMaxId;
        }

        MediaCaptureStats media = mediaSink == null
                ? new MediaCaptureStats(0, 0, 0, 0)
                : mediaSink.stats();
        return new ArchiveResult(
                account, statusesArchived, pages, media,
                newestIdentity, maxId, complete);
    }

    public record ArchiveResult(
            Account account,
            int statusesArchived,
            int pagesFetched,
            MediaCaptureStats media,
            String newestIdentity,
            String nextCursor,
            boolean complete) {
    }
}
