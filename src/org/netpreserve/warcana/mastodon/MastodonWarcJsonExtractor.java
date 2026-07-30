package org.netpreserve.warcana.mastodon;

import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.SocialResults;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.model.Site;
import org.netpreserve.warcana.mastodon.model.Status;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Extracts Mastodon account-status API responses from a WARC file.
 */
public class MastodonWarcJsonExtractor {
    public SocialResults extract(Path input) throws IOException {
        ArrayList<Post> posts = new ArrayList<>();
        Set<String> seenStatuses = new HashSet<>();

        try (var reader = new WarcReader(input)) {
            while (true) {
                var next = reader.next();
                if (next.isEmpty()) {
                    break;
                }
                if (!(next.get() instanceof WarcResponse response) || !isAccountStatusesResponse(response)) {
                    continue;
                }

                var http = response.http();
                if (http.status() < 200 || http.status() >= 300) {
                    continue;
                }

                try {
                    Status[] statuses =
                            SocialJson.mapper.readValue(http.bodyDecoded().stream(), Status[].class);
                    if (statuses == null) {
                        throw new IOException("Status response was JSON null");
                    }
                    Site site = new Site(response.targetURI().getHost());
                    for (Status status : statuses) {
                        String key = status.uri() == null
                                ? response.targetURI().getAuthority() + ":" + status.id()
                                : status.uri();
                        if (seenStatuses.add(key)) {
                            posts.add(status.toGenericPost(site));
                        }
                    }
                } catch (Exception e) {
                    throw new IOException("Invalid Mastodon status response at WARC offset " +
                            response.position() + " (" + response.target() + ")", e);
                }
            }
        }

        return new SocialResults(posts.size(), posts);
    }

    private static boolean isAccountStatusesResponse(WarcResponse response) {
        if (!response.contentType().base().equals(org.netpreserve.jwarc.MediaType.HTTP)) {
            return false;
        }
        String path = response.targetURI().getPath();
        return path != null && path.matches("^/api/v1/accounts/[^/]+/statuses/?$");
    }
}
