package org.netpreserve.socialwarc.twitter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "__typename")
@JsonTypeName("User")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserV2(
        String id,
        String restId,
        boolean isBlueVerified,
        Object affiliatesHighlightedLabel,
        String profileImageShape,
        User legacy,
        Object legacyExtendedProfile,
        Boolean isProfileTranslatable,
        Object verificationInfo,
        Object businessAccount
) implements UserResponse.UserOrError {
}
