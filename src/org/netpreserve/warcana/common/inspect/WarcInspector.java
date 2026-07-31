package org.netpreserve.warcana.common.inspect;

import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.common.WarcPostScanner;
import org.netpreserve.warcana.common.WarcResponseExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class WarcInspector {
    private final List<WarcResponseExtractor> extractors;

    public WarcInspector(List<WarcResponseExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public WarcInspection inspect(Path input) throws IOException {
        long records = 0;
        long digestsChecked = 0;
        long digestMismatches = 0;
        Instant earliest = null;
        Instant latest = null;
        var recordTypes = new TreeMap<String, Long>();
        var httpStatuses = new TreeMap<String, Long>();
        var warnings = new ArrayList<String>();

        try (var reader = new WarcReader(input)) {
            reader.onWarning(warnings::add);
            reader.calculateBlockDigest();
            while (true) {
                var next = reader.next();
                if (next.isEmpty()) {
                    break;
                }
                var record = next.get();
                records++;
                recordTypes.merge(record.type(), 1L, Long::sum);
                Instant date = record.date();
                if (earliest == null || date.isBefore(earliest)) {
                    earliest = date;
                }
                if (latest == null || date.isAfter(latest)) {
                    latest = date;
                }
                if (record instanceof WarcResponse response) {
                    try {
                        int status = response.http().status();
                        httpStatuses.merge(Integer.toString(status), 1L, Long::sum);
                    } catch (IOException | RuntimeException ignored) {
                        // Resource responses need not contain an HTTP message.
                    }
                }
                var expected = record.blockDigest();
                if (expected.isPresent()) {
                    var calculated = record.calculatedBlockDigest();
                    if (calculated.isPresent()) {
                        digestsChecked++;
                        if (!Arrays.equals(
                                expected.get().bytes(),
                                calculated.get().bytes())) {
                            digestMismatches++;
                        }
                    }
                }
            }
        }

        var postsByPlatform = new TreeMap<String, Long>();
        long posts = new WarcPostScanner(extractors).scan(input, scanned ->
                postsByPlatform.merge(scanned.platform(), 1L, Long::sum));
        return new WarcInspection(
                "warc",
                input.toAbsolutePath().normalize().toString(),
                Files.size(input),
                records,
                new LinkedHashMap<>(recordTypes),
                new LinkedHashMap<>(httpStatuses),
                earliest == null ? null : earliest.toString(),
                latest == null ? null : latest.toString(),
                posts,
                new LinkedHashMap<>(postsByPlatform),
                digestsChecked,
                digestMismatches,
                List.copyOf(warnings));
    }
}
