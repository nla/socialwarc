package org.netpreserve.warcana.bluesky;

import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.bluesky.model.FeedPage;
import org.netpreserve.warcana.common.ExtractedPost;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.WarcResponseExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BlueskyWarcResponseExtractor implements WarcResponseExtractor {
    @Override
    public String platform() {
        return "bluesky";
    }

    @Override
    public boolean supports(WarcResponse response) {
        return response.contentType().base().equals(MediaType.HTTP) &&
                "/xrpc/app.bsky.feed.getAuthorFeed".equals(response.targetURI().getPath());
    }

    @Override
    public List<ExtractedPost> extract(WarcResponse response) throws IOException {
        var http = response.http();
        if (http.status() < 200 || http.status() >= 300) {
            return Collections.emptyList();
        }
        try {
            FeedPage page = SocialJson.mapper.readValue(
                    http.bodyDecoded().stream(), FeedPage.class);
            if (page == null || page.feed() == null) {
                throw new IOException("Author feed response did not contain a feed");
            }
            ArrayList<ExtractedPost> posts = new ArrayList<>(page.feed().size());
            for (var item : page.feed()) {
                if (item != null) {
                    posts.add(new ExtractedPost(item.identity(), item.toGenericPost()));
                }
            }
            return posts;
        } catch (Exception e) {
            throw new IOException("Invalid Bluesky author-feed response at WARC offset " +
                    response.position() + " (" + response.target() + ")", e);
        }
    }
}
