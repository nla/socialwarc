package org.netpreserve.warcana;

import org.netpreserve.warcana.bluesky.BlueskyWarcResponseExtractor;
import org.netpreserve.warcana.common.WarcResponseExtractor;
import org.netpreserve.warcana.mastodon.MastodonWarcResponseExtractor;
import org.netpreserve.warcana.twitter.TwitterWarcResponseExtractor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

public final class CliSupport {
    enum Platform {
        MASTODON,
        BLUESKY,
        TWITTER;

        static Platform parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown platform '" + value +
                                "' (expected mastodon, bluesky or twitter)");
            }
        }
    }

    public static List<WarcResponseExtractor> responseExtractors() {
        return List.of(
                new MastodonWarcResponseExtractor(),
                new BlueskyWarcResponseExtractor(),
                new TwitterWarcResponseExtractor());
    }

    public static Instant parseDate(String value, boolean endOfDay) {
        try {
            if (value.contains("T")) {
                return Instant.parse(value);
            }
            LocalDate date = LocalDate.parse(value);
            return endOfDay
                    ? date.plusDays(1).atStartOfDay(ZoneOffset.UTC)
                            .toInstant().minusMillis(1)
                    : date.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid date '" + value +
                            "' (expected YYYY-MM-DD or an ISO-8601 instant)");
        }
    }

    static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private CliSupport() {
    }
}
