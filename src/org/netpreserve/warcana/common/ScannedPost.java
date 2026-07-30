package org.netpreserve.warcana.common;

import org.netpreserve.warcana.common.model.Post;

public record ScannedPost(
        String platform,
        String identity,
        Post post,
        String sourceTarget,
        String warcRecordId) {
}
