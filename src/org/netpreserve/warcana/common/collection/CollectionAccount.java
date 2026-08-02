package org.netpreserve.warcana.common.collection;

public record CollectionAccount(String platform, String account) {
    public boolean localTimeline() {
        return "mastodon-local".equals(platform);
    }
}
