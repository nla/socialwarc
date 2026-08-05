package org.netpreserve.socialwarc.common.inspect;

import java.util.List;
import java.util.Map;

public record CollectionInspection(
        String kind,
        String path,
        int configuredAccounts,
        int trackedAccounts,
        int activeJobs,
        boolean indexPresent,
        long completedBytes,
        Map<String, FileSet> completedByKind,
        List<WarcFile> warcs) {

    public record FileSet(long files, long bytes) {
    }

    public record WarcFile(
            String path,
            String kind,
            String state,
            long sizeBytes) {
    }
}
