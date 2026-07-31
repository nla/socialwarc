package org.netpreserve.warcana.mastodon;

import org.netpreserve.warcana.mastodon.model.Account;
import org.netpreserve.warcana.mastodon.model.Status;

import java.io.IOException;
import java.util.List;

interface MastodonApi {
    Account lookupAccount(String acct) throws IOException;

    List<Status> getAccountStatuses(
            String accountId, String sinceId, String maxId, int limit) throws IOException;
}
