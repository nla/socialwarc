package org.netpreserve.warcana.twitter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.NonNull;
import org.netpreserve.warcana.common.model.Account;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record User(
        long id,
        @NonNull String name,
        @NonNull String screenName,
        @NonNull String profileImageUrlHttps,
        String profileBannerUrl
) {
    public Account toGenericAccount() {
        return new Account(screenName, name, profileImageUrlHttps, profileBannerUrl);
    }
}
