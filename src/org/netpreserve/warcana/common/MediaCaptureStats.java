package org.netpreserve.warcana.common;

public record MediaCaptureStats(
        int captured,
        int failed,
        int skipped,
        long bytes) {
}
