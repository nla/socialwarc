package org.netpreserve.socialwarc.mastodon;

import org.netpreserve.socialwarc.mastodon.model.Account;
import org.netpreserve.socialwarc.mastodon.model.Status;

import java.io.IOException;
import java.util.List;

interface MastodonApi {
    Account lookupAccount(String acct) throws IOException;

    List<Status> getAccountStatuses(
            String accountId, String sinceId, String maxId, int limit) throws IOException;

    List<Status> getPublicTimeline(
            boolean local, String minId, String maxId, int limit) throws IOException;
}
