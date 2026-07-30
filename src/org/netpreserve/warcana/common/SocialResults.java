package org.netpreserve.warcana.common;

import org.netpreserve.warcana.common.model.Post;

import java.util.List;

public record SocialResults(long totalHits, List<Post> posts) {
}
