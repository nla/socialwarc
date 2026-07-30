package org.netpreserve.warcana.bluesky;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;

record FeedPage(@Nullable String cursor, List<JsonNode> feed) {
}
