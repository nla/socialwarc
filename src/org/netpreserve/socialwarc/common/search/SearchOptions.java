package org.netpreserve.socialwarc.common.search;

import java.time.Instant;

public record SearchOptions(
        String query,
        String platform,
        String author,
        Instant since,
        Instant until,
        boolean hasMedia,
        SearchSort sort,
        int limit,
        int offset) {
}
