package org.netpreserve.warcana.common;

import java.util.Collection;

public interface MediaSink {
    void captureAll(Collection<String> urls);

    MediaCaptureStats stats();
}
