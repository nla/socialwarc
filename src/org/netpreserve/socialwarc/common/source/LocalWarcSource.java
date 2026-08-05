package org.netpreserve.socialwarc.common.source;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public record LocalWarcSource(Path path) implements WarcSource {
    public LocalWarcSource {
        path = path.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return filename();
    }

    @Override
    public String filename() {
        return path.getFileName().toString();
    }

    @Override
    public long size() throws IOException {
        return Files.size(path);
    }

    @Override
    public String version() throws IOException {
        return size() + ":" + Files.getLastModifiedTime(path).toMillis();
    }

    @Override
    public SeekableByteChannel open() throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ);
    }
}
