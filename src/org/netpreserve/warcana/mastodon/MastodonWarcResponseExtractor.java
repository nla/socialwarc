package org.netpreserve.warcana.mastodon;

import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.common.ExtractedPost;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.WarcResponseExtractor;
import org.netpreserve.warcana.common.model.Site;
import org.netpreserve.warcana.mastodon.model.Status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MastodonWarcResponseExtractor implements WarcResponseExtractor {
    @Override
    public String platform() {
        return "mastodon";
    }

    @Override
    public boolean supports(WarcResponse response) {
        if (!response.contentType().base().equals(MediaType.HTTP)) {
            return false;
        }
        String path = response.targetURI().getPath();
        return path != null && path.matches("^/api/v1/accounts/[^/]+/statuses/?$");
    }

    @Override
    public List<ExtractedPost> extract(WarcResponse response) throws IOException {
        var http = response.http();
        if (http.status() < 200 || http.status() >= 300) {
            return Collections.emptyList();
        }
        try {
            Status[] statuses =
                    SocialJson.mapper.readValue(http.bodyDecoded().stream(), Status[].class);
            if (statuses == null) {
                throw new IOException("Status response was JSON null");
            }
            Site site = new Site(response.targetURI().getHost());
            ArrayList<ExtractedPost> posts = new ArrayList<>(statuses.length);
            for (Status status : statuses) {
                String key = status.uri() == null
                        ? response.targetURI().getAuthority() + ":" + status.id()
                        : status.uri();
                posts.add(new ExtractedPost(key, status.toGenericPost(site)));
            }
            return posts;
        } catch (Exception e) {
            throw new IOException("Invalid Mastodon status response at WARC offset " +
                    response.position() + " (" + response.target() + ")", e);
        }
    }
}
