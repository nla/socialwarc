package org.netpreserve.warcana.common.collection;

/**
 * Paths and durable write position for one collection WARC stream.
 */
public class WarcStreamState {
    public String openWarc;
    public String finalWarc;
    public long committedOffset;

    public WarcStreamState() {
    }

    public String openWarc() {
        return openWarc;
    }

    public String finalWarc() {
        return finalWarc;
    }

    public long committedOffset() {
        return committedOffset;
    }

    public void setPaths(String open, String completed) {
        openWarc = open;
        finalWarc = completed;
    }

    public void setCommittedOffset(long offset) {
        committedOffset = offset;
    }

    public void copyFrom(WarcStreamState source) {
        openWarc = source.openWarc;
        finalWarc = source.finalWarc;
        committedOffset = source.committedOffset;
    }

    public void clear() {
        openWarc = null;
        finalWarc = null;
        committedOffset = 0;
    }
}
