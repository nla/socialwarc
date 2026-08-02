package org.netpreserve.warcana.common.search;

import java.util.Locale;

public enum SearchSort {
    AUTO,
    RELEVANCE,
    NEWEST,
    OLDEST;

    public static SearchSort parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            SearchSort sort = valueOf(value.toUpperCase(Locale.ROOT));
            if (sort == AUTO) {
                throw new IllegalArgumentException();
            }
            return sort;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown sort order '" + value +
                            "' (expected relevance, newest or oldest)");
        }
    }
}
