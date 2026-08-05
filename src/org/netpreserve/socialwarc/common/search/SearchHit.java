package org.netpreserve.socialwarc.common.search;

import org.netpreserve.socialwarc.common.model.Post;

import java.time.Instant;

public record SearchHit(
        Float score,
        String snippet,
        String platform,
        String identity,
        String sourceTarget,
        String warcRecordId,
        String sourceWarc,
        long warcOffset,
        Instant warcDate,
        Post post) {
}
