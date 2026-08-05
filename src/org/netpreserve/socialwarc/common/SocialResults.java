package org.netpreserve.socialwarc.common;

import org.netpreserve.socialwarc.common.model.Post;

import java.util.List;

public record SocialResults(long totalHits, List<Post> posts) {
}
