package org.netpreserve.warcana.common.source;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;

/** Read-only seekable channel backed by HTTP byte-range requests. */
public final class HttpRangeChannel implements SeekableByteChannel {
    private static final int READ_AHEAD = 8 * 1024 * 1024;

    @FunctionalInterface
    public interface HeaderSupplier {
        String[] headers() throws IOException;
    }

    private final HttpClient client;
    private final URI uri;
    private final long size;
    private final String expectedDigest;
    private final HeaderSupplier headers;
    private boolean open = true;
    private long position;
    private long bufferStart;
    private byte[] buffer = new byte[0];

    public HttpRangeChannel(
            HttpClient client,
            URI uri,
            long size,
            String expectedDigest,
            HeaderSupplier headers) {
        this.client = client;
        this.uri = uri;
        this.size = size;
        this.expectedDigest = expectedDigest;
        this.headers = headers;
    }

    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        if (position >= size) {
            return -1;
        }
        if (position < bufferStart || position >= bufferStart + buffer.length) {
            fill();
        }
        int offset = Math.toIntExact(position - bufferStart);
        int length = Math.min(
                destination.remaining(), buffer.length - offset);
        destination.put(buffer, offset, length);
        position += length;
        return length;
    }

    private void fill() throws IOException {
        long end = Math.min(size - 1, position + READ_AHEAD - 1);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Range", "bytes=" + position + "-" + end)
                .headers(headers.headers())
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading remote WARC", e);
        }
        if (response.statusCode() != 206) {
            throw new IOException("Expected HTTP 206 reading " + uri +
                    " but received " + response.statusCode());
        }
        String expectedRange = "bytes " + position + "-" +
                (position + response.body().length - 1) + "/" + size;
        String actualRange = response.headers().firstValue("Content-Range")
                .orElse("");
        if (!expectedRange.equals(actualRange)) {
            throw new IOException("Unexpected Content-Range reading " + uri +
                    ": " + actualRange);
        }
        if (expectedDigest != null) {
            String actualDigest = response.headers().firstValue("Digest")
                    .orElse(null);
            if (actualDigest != null && !actualDigest.equals(expectedDigest)) {
                throw new IOException("Remote WARC digest changed: " + uri);
            }
        }
        bufferStart = position;
        buffer = response.body();
        if (buffer.length == 0) {
            throw new IOException("Empty HTTP range response reading " + uri);
        }
    }

    @Override
    public int write(ByteBuffer source) {
        throw new UnsupportedOperationException("read-only channel");
    }

    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0 || newPosition > size) {
            throw new IllegalArgumentException("position out of range");
        }
        position = newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long ignored) {
        throw new UnsupportedOperationException("read-only channel");
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        buffer = new byte[0];
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
