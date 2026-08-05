package org.netpreserve.socialwarc.bamboo;

import org.netpreserve.socialwarc.common.source.HttpRangeChannel;
import org.netpreserve.socialwarc.common.source.WarcSource;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.Base64;
import java.util.HexFormat;

record BambooWarcSource(
        BambooClient client,
        long warcId,
        long crawlId,
        String filename,
        long size,
        String sha256) implements WarcSource {
    @Override
    public String id() {
        return "bamboo:" + warcId;
    }

    @Override
    public String version() {
        return sha256 == null ? Long.toString(size) : sha256;
    }

    @Override
    public SeekableByteChannel open() throws IOException {
        String digest = sha256 == null
                ? null
                : "sha-256=" + Base64.getEncoder().encodeToString(
                        HexFormat.of().parseHex(sha256));
        return new HttpRangeChannel(
                client.httpClient(),
                client.configuration().resolve("/warcs/" + warcId),
                size, digest, client::authorizationHeaders);
    }
}
