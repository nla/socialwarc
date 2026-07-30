package org.netpreserve.warcana.bluesky;

import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.bluesky.model.FeedPage;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.SocialResults;
import org.netpreserve.warcana.common.model.Post;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Extracts Bluesky author-feed responses from a WARC into the common social model.
 */
public class BlueskyWarcJsonExtractor {
    public SocialResults extract(Path input) throws IOException {
        ArrayList<Post> posts = new ArrayList<>();
        Set<String> seenItems = new HashSet<>();

        try (var reader = new WarcReader(input)) {
            while (true) {
                var next = reader.next();
                if (next.isEmpty()) {
                    break;
                }
                if (!(next.get() instanceof WarcResponse response) || !isAuthorFeedResponse(response)) {
                    continue;
                }

                var http = response.http();
                if (http.status() < 200 || http.status() >= 300) {
                    continue;
                }
                try {
                    FeedPage page = SocialJson.mapper.readValue(
                            http.bodyDecoded().stream(), FeedPage.class);
                    if (page == null || page.feed() == null) {
                        throw new IOException("Author feed response did not contain a feed");
                    }
                    for (var item : page.feed()) {
                        if (item != null && seenItems.add(item.identity())) {
                            posts.add(item.toGenericPost());
                        }
                    }
                } catch (Exception e) {
                    throw new IOException("Invalid Bluesky author-feed response at WARC offset " +
                            response.position() + " (" + response.target() + ")", e);
                }
            }
        }

        return new SocialResults(posts.size(), posts);
    }

    private static boolean isAuthorFeedResponse(WarcResponse response) {
        if (!response.contentType().base().equals(MediaType.HTTP)) {
            return false;
        }
        return "/xrpc/app.bsky.feed.getAuthorFeed".equals(response.targetURI().getPath());
    }
}
