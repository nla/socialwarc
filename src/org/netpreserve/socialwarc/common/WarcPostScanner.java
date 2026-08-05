package org.netpreserve.socialwarc.common;

import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.netpreserve.socialwarc.common.source.LocalWarcSource;
import org.netpreserve.socialwarc.common.source.WarcSource;

/**
 * Streams deduplicated common-model posts from a WARC.
 */
public class WarcPostScanner {
    private final List<WarcResponseExtractor> extractors;

    public WarcPostScanner(List<WarcResponseExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public long scan(Path input, PostConsumer consumer) throws IOException {
        return scan(List.of(input), consumer);
    }

    public long scan(List<Path> inputs, PostConsumer consumer) throws IOException {
        return scanSources(inputs.stream().map(LocalWarcSource::new).toList(),
                consumer);
    }

    public long scanSources(
            List<? extends WarcSource> inputs, PostConsumer consumer)
            throws IOException {
        Set<String> seen = new HashSet<>();
        long count = 0;

        for (WarcSource input : inputs) {
            count += scanOne(input, consumer, seen);
        }
        return count;
    }

    private long scanOne(
            WarcSource input, PostConsumer consumer, Set<String> seen)
            throws IOException {
        long count = 0;
        try (var reader = new WarcReader(input.open())) {
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
                        if (!seen.add(identity)) {
                            continue;
                        }
                        consumer.accept(new ScannedPost(
                                extractor.platform(),
                                extracted.identity(),
                                extracted.post(),
                                response.target().toString(),
                                response.id().toString(),
                                input.id(),
                                response.position(),
                                response.date()));
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @FunctionalInterface
    public interface PostConsumer {
        void accept(ScannedPost post) throws IOException;
    }
}
