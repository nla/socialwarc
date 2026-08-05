package org.netpreserve.socialwarc.common;

import java.util.Collection;

public interface MediaSink {
    void captureAll(Collection<String> urls);

    default void discover(Collection<String> urls) {
        captureAll(urls);
    }

    default void discoverVideo(Collection<String> urls) {
        discover(urls);
    }

    default void drain() {
    }

    MediaCaptureStats stats();
}
