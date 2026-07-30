package org.netpreserve.warcana.common.model;

import com.fasterxml.jackson.annotation.JsonAlias;

public record Account(
        String username,
        String displayName,
        @JsonAlias("avatar")
        String avatarUrl,
        String bannerUrl) {
}
