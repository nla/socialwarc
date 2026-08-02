package org.netpreserve.warcana;

import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.web.WarcanaServer;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

final class ServeCommand {
    static int run(String[] args, PrintStream out, PrintStream err) {
        Path collection = null;
        String host = "127.0.0.1";
        int port = 8080;
        boolean reindex = false;
        boolean quiet = false;

        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        usage(out);
                        return 0;
                    }
                    case "--host" -> host = CliSupport.requireValue(
                            args, ++i, args[i - 1]);
                    case "--port" -> port = Integer.parseInt(
                            CliSupport.requireValue(args, ++i, args[i - 1]));
                    case "--reindex" -> reindex = true;
                    case "-q", "--quiet" -> quiet = true;
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException(
                                    "Unknown option: " + args[i]);
                        }
                        if (collection != null) {
                            throw new IllegalArgumentException(
                                    "Only one collection may be served");
                        }
                        collection = Path.of(args[i]);
                    }
                }
            }
            if (collection == null) {
                throw new IllegalArgumentException("Missing collection");
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException(
                        "--port must be between 0 and 65535");
            }

            ProgressReporter progress = quiet
                    ? ProgressReporter.NONE
                    : err::println;
            try (var server = new WarcanaServer(
                    collection,
                    new InetSocketAddress(host, port),
                    reindex,
                    progress)) {
                server.start();
                out.println("Viewing collection at " + server.baseUri());
                var stopped = new CountDownLatch(1);
                Thread hook = new Thread(stopped::countDown, "warcana-serve-stop");
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    stopped.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (IllegalStateException ignored) {
                        // The JVM is already shutting down.
                    }
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("serve failed: " + e.getMessage());
            return 1;
        }
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: warcana serve [options] <collection>");
        stream.println();
        stream.println("Start a local collection search and management interface.");
        stream.println();
        stream.println("Options:");
        stream.println("  --host HOST       Address to bind (default: 127.0.0.1)");
        stream.println("  --port PORT       Port to bind (default: 8080; 0 chooses one)");
        stream.println("  --reindex         Rebuild post and resource indexes");
        stream.println("  -q, --quiet       Suppress indexing progress");
        stream.println("  -h, --help        Show this help");
    }
}
