package org.netpreserve.warcana.common;

import org.netpreserve.warcana.common.model.Post;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a WARC once and dispatches response records to platform extractors.
 */
public class WarcJsonExtractor {
    private final List<WarcResponseExtractor> extractors;

    public WarcJsonExtractor(List<WarcResponseExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public SocialResults extract(Path input) throws IOException {
        ArrayList<Post> posts = new ArrayList<>();
        new WarcPostScanner(extractors).scan(input, scanned -> posts.add(scanned.post()));
        return new SocialResults(posts.size(), posts);
    }
}
