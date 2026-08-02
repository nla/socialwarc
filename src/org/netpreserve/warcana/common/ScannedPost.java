package org.netpreserve.warcana.common;

import org.netpreserve.warcana.common.model.Post;

import java.time.Instant;

public record ScannedPost(
        String platform,
        String identity,
        Post post,
        String sourceTarget,
        String warcRecordId,
        String sourceWarc,
        long warcOffset,
        Instant warcDate) {
}
