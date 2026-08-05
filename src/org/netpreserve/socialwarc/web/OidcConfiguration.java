package org.netpreserve.socialwarc.web;

import java.net.URI;

public record OidcConfiguration(
        URI issuer,
        String clientId,
        String clientSecret,
        URI publicUrl,
        String manageRole) {
    public OidcConfiguration {
        if (issuer == null || !issuer.isAbsolute()) {
            throw new IllegalArgumentException("OIDC issuer must be an absolute URL");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("OIDC client ID must not be blank");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("OIDC client secret must not be blank");
        }
        if (publicUrl == null || !publicUrl.isAbsolute()) {
            throw new IllegalArgumentException(
                    "A public URL is required when OIDC is enabled");
        }
        if (publicUrl.getQuery() != null || publicUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Public URL must not contain a query or fragment");
        }
        if (publicUrl.getPath() != null &&
                !publicUrl.getPath().isEmpty() &&
                !"/".equals(publicUrl.getPath())) {
            throw new IllegalArgumentException(
                    "Public URL must not contain a path");
        }
        if (manageRole == null || manageRole.isBlank()) {
            throw new IllegalArgumentException("OIDC manage role must not be blank");
        }
        issuer = stripTrailingSlash(issuer);
        publicUrl = ensureTrailingSlash(publicUrl);
    }

    public URI callbackUrl() {
        return publicUrl.resolve("oidc/callback");
    }

    private static URI stripTrailingSlash(URI uri) {
        String value = uri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value);
    }

    private static URI ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? uri : URI.create(value + "/");
    }
}
