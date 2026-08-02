package org.netpreserve.warcana.common.source;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;

/** An immutable local or remote WARC object. */
public interface WarcSource {
    String id();

    String filename();

    long size() throws IOException;

    /** Stable value that changes whenever the source bytes change. */
    String version() throws IOException;

    SeekableByteChannel open() throws IOException;
}
