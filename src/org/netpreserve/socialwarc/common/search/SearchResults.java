package org.netpreserve.socialwarc.common.search;

import java.util.List;

public record SearchResults(long totalHits, List<SearchHit> hits) {
}
