package org.netpreserve.warcana.common.resource;

public record ArchivedResource(
        String id,
        String url,
        String warcFilename,
        long warcOffset,
        String warcRecordId,
        String contentType) {
}
