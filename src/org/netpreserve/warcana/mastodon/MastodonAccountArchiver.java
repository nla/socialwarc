package org.netpreserve.warcana.mastodon;

import org.netpreserve.jwarc.WarcWriter;
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

    public MastodonAccountArchiver(String server, String userAgent, WarcWriter warcWriter, int pageSize) {
        if (pageSize < 1 || pageSize > 40) {
            throw new IllegalArgumentException("Page size must be between 1 and 40");
        }
        this.client = new MastodonClient(server, userAgent, warcWriter);
        this.pageSize = pageSize;
    }

    public ArchiveResult archive(String accountName) throws IOException {
        Account account = client.lookupAccount(accountName);
        String maxId = null;
        int pages = 0;
        int statusesArchived = 0;
        Set<String> cursors = new HashSet<>();

        while (true) {
            var statuses = client.getAccountStatuses(account.id(), null, maxId, pageSize);
            pages++;
            statusesArchived += statuses.size();
            if (statuses.isEmpty()) {
                break;
            }

            String nextMaxId = statuses.get(statuses.size() - 1).id();
            if (!cursors.add(nextMaxId)) {
                throw new IOException("Mastodon pagination repeated status ID " + nextMaxId);
            }
            maxId = nextMaxId;
        }

        return new ArchiveResult(account, statusesArchived, pages);
    }

    public record ArchiveResult(Account account, int statusesArchived, int pagesFetched) {
    }
}
