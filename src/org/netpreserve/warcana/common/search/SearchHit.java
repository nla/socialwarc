package org.netpreserve.warcana.common.search;

import org.netpreserve.warcana.common.model.Post;

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
