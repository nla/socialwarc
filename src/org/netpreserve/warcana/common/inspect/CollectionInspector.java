package org.netpreserve.warcana.common.inspect;

import org.netpreserve.warcana.common.collection.CollectionState;
import org.netpreserve.warcana.common.collection.CollectionStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class CollectionInspector {
    public CollectionInspection inspect(CollectionStore store) throws IOException {
        CollectionState state = store.loadCollectionState();
        var files = new ArrayList<CollectionInspection.WarcFile>();
        var totals = new LinkedHashMap<String, long[]>();
        long completedBytes = 0;

        for (Path path : store.completedWarcs()) {
            long size = Files.size(path);
            String kind = kind(path);
            files.add(new CollectionInspection.WarcFile(
                    path.toAbsolutePath().normalize().toString(),
                    kind,
                    "completed",
                    size));
            long[] total = totals.computeIfAbsent(kind, ignored -> new long[2]);
            total[0]++;
            total[1] += size;
            completedBytes += size;
        }

        addOpen(files, store, state.openWarc, "posts");
        addOpen(files, store, state.mediaOpenWarc, "media");
        addOpen(files, store, state.videoOpenWarc, "video");

        var completedByKind =
                new LinkedHashMap<String, CollectionInspection.FileSet>();
        for (String kind : new String[]{"posts", "media", "video"}) {
            long[] total = totals.getOrDefault(kind, new long[2]);
            completedByKind.put(
                    kind,
                    new CollectionInspection.FileSet(total[0], total[1]));
        }

        return new CollectionInspection(
                "collection",
                store.root().toString(),
                store.readAccounts().size(),
                state.accounts.size(),
                store.activeJobFiles().size(),
                Files.isDirectory(store.indexDirectory()),
                completedBytes,
                completedByKind,
                List.copyOf(files));
    }

    private static void addOpen(
            ArrayList<CollectionInspection.WarcFile> files,
            CollectionStore store,
            String relative,
            String kind) throws IOException {
        if (relative == null) {
            return;
        }
        Path path = store.root().resolve(relative);
        boolean exists = Files.isRegularFile(path);
        files.add(new CollectionInspection.WarcFile(
                path.toAbsolutePath().normalize().toString(),
                kind,
                exists ? "open" : "missing",
                exists ? Files.size(path) : 0));
    }

    private static String kind(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith("warcana-media-")) {
            return "media";
        }
        if (name.startsWith("warcana-video-")) {
            return "video";
        }
        return "posts";
    }
}
