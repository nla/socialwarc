package org.netpreserve.socialwarc.common;

import org.netpreserve.socialwarc.common.model.Post;

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
