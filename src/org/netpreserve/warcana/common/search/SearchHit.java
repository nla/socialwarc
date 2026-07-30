package org.netpreserve.warcana.common.search;

import org.netpreserve.warcana.common.model.Post;

public record SearchHit(
        Float score,
        String snippet,
        String platform,
        String identity,
        String sourceTarget,
        String warcRecordId,
        Post post) {
}
