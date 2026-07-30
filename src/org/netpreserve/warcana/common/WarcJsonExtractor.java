package org.netpreserve.warcana.common;

import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.common.model.Post;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> seen = new HashSet<>();

        try (var reader = new WarcReader(input)) {
            while (true) {
                var next = reader.next();
                if (next.isEmpty()) {
                    break;
                }
                if (!(next.get() instanceof WarcResponse response)) {
                    continue;
                }
                for (var extractor : extractors) {
                    if (!extractor.supports(response)) {
                        continue;
                    }
                    for (var extracted : extractor.extract(response)) {
                        String identity = extractor.platform() + ":" + extracted.identity();
                        if (seen.add(identity)) {
                            posts.add(extracted.post());
                        }
                    }
                }
            }
        }

        return new SocialResults(posts.size(), posts);
    }
}
