package org.netpreserve.warcana.common.search;

import java.time.Instant;

public record SearchOptions(
        String query,
        String platform,
        String author,
        Instant since,
        Instant until,
        boolean hasMedia,
        int limit,
        int offset) {
}
