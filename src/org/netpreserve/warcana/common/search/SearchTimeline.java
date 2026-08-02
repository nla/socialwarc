package org.netpreserve.warcana.common.search;

import java.time.LocalDate;
import java.util.List;

public record SearchTimeline(
        LocalDate start,
        LocalDate end,
        Unit unit,
        List<Bucket> buckets) {

    public enum Unit {
        DAY,
        WEEK,
        MONTH,
        YEAR
    }

    public record Bucket(LocalDate start, LocalDate end, long count) {
    }

    public static SearchTimeline empty() {
        return new SearchTimeline(null, null, Unit.DAY, List.of());
    }
}
