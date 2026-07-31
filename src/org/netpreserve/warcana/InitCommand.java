package org.netpreserve.warcana;

import org.netpreserve.warcana.common.collection.CollectionStore;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

final class InitCommand {
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 2 &&
                ("-h".equals(args[1]) || "--help".equals(args[1]))) {
            out.println("Usage: warcana init COLLECTION_DIRECTORY");
            return 0;
        }
        if (args.length != 2) {
            err.println("error: init requires exactly one collection directory");
            return 2;
        }
        try {
            var store = new CollectionStore(Path.of(args[1]));
            store.initialize();
            out.println("Initialized collection: " + store.root());
            out.println("Edit " + store.accountsFile() + " and run:");
            out.println("  warcana archive --collection " + store.root());
            return 0;
        } catch (IOException e) {
            err.println("initialization failed: " + e.getMessage());
            return 1;
        }
    }

    private InitCommand() {
    }
}
