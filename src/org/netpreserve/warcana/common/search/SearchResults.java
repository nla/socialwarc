package org.netpreserve.warcana.common.search;

import java.util.List;

public record SearchResults(long totalHits, List<SearchHit> hits) {
}
