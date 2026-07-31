package org.netpreserve.warcana.mastodon;

import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.warcana.common.MediaCaptureStats;
import org.netpreserve.warcana.common.MediaSink;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.mastodon.model.Account;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Archives the account lookup and every page of public statuses returned by a Mastodon server.
 */
public class MastodonAccountArchiver {
    private final MastodonClient client;
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

    public ArchiveResult archive(String accountName) throws IOException {
        progress.report("Looking up Mastodon account: " + accountName);
        Account account = client.lookupAccount(accountName);
        progress.report("Found Mastodon account: " + account.acct());
        if (mediaSink != null) {
            mediaSink.captureAll(account.mediaUrls());
        }
        String maxId = null;
        int pages = 0;
        int statusesArchived = 0;
        Set<String> cursors = new HashSet<>();

        while (true) {
            progress.report("Fetching Mastodon statuses page " + (pages + 1) + "...");
            var statuses = client.getAccountStatuses(account.id(), null, maxId, pageSize);
            pages++;
            statusesArchived += statuses.size();
            progress.report("Fetched " + statuses.size() +
                    (statuses.size() == 1 ? " status (" : " statuses (") +
                    statusesArchived + " total)");
            if (mediaSink != null) {
                statuses.forEach(status ->
                        mediaSink.captureAll(status.mediaUrls(includeVideo)));
            }
            if (statuses.isEmpty()) {
                break;
            }

            String nextMaxId = statuses.get(statuses.size() - 1).id();
            if (!cursors.add(nextMaxId)) {
                throw new IOException("Mastodon pagination repeated status ID " + nextMaxId);
            }
            maxId = nextMaxId;
        }

        MediaCaptureStats media = mediaSink == null
                ? new MediaCaptureStats(0, 0, 0, 0)
                : mediaSink.stats();
        return new ArchiveResult(account, statusesArchived, pages, media);
    }

    public record ArchiveResult(
            Account account,
            int statusesArchived,
            int pagesFetched,
            MediaCaptureStats media) {
    }
}
