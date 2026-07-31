package org.netpreserve.warcana.common.inspect;

import java.util.List;
import java.util.Map;

public record WarcInspection(
        String kind,
        String path,
        long sizeBytes,
        long records,
        Map<String, Long> recordTypes,
        Map<String, Long> httpStatuses,
        String earliestRecord,
        String latestRecord,
        long posts,
        Map<String, Long> postsByPlatform,
        long blockDigestsChecked,
        long blockDigestMismatches,
        List<String> warnings) {
}
