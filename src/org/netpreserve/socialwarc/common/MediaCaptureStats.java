package org.netpreserve.socialwarc.common;

public record MediaCaptureStats(
        int captured,
        int failed,
        int skipped,
        long bytes) {
}
