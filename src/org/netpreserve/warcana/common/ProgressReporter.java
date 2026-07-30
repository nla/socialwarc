package org.netpreserve.warcana.common;

@FunctionalInterface
public interface ProgressReporter {
    ProgressReporter NONE = message -> {
    };

    void report(String message);
}
