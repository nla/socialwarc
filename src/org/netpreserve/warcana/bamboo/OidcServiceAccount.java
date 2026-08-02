package org.netpreserve.warcana.bamboo;

import com.nimbusds.oauth2.sdk.ClientCredentialsGrant;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import org.netpreserve.warcana.web.OidcConfiguration;

import java.io.IOException;
import java.time.Instant;

final class OidcServiceAccount {
    private static final long EXPIRY_SLOP_SECONDS = 30;

    private final OidcConfiguration configuration;
    private OIDCProviderMetadata metadata;
    private AccessToken token;
    private Instant expiresAt = Instant.MIN;

    OidcServiceAccount(OidcConfiguration configuration) {
        this.configuration = configuration;
    }

    synchronized String bearer() throws IOException {
        if (token == null || !Instant.now().isBefore(expiresAt)) {
            refresh();
        }
        return token.toAuthorizationHeader();
    }

    synchronized void invalidate() {
        token = null;
        expiresAt = Instant.MIN;
    }

    private void refresh() throws IOException {
        try {
            var request = new TokenRequest(
                    metadata().getTokenEndpointURI(),
                    new ClientSecretBasic(
                            new ClientID(configuration.clientId()),
                            new Secret(configuration.clientSecret())),
                    new ClientCredentialsGrant(),
                    new Scope("openid"));
            TokenResponse response = TokenResponse.parse(
                    request.toHTTPRequest().send());
            if (!response.indicatesSuccess()) {
                throw new IOException("OIDC service-account login failed: " +
                        response.toErrorResponse().getErrorObject());
            }
            token = response.toSuccessResponse().getTokens().getAccessToken();
            expiresAt = Instant.now().plusSeconds(Math.max(
                    0, token.getLifetime() - EXPIRY_SLOP_SECONDS));
        } catch (com.nimbusds.oauth2.sdk.ParseException e) {
            throw new IOException("Invalid OIDC token response", e);
        }
    }

    private OIDCProviderMetadata metadata() throws IOException {
        if (metadata == null) {
            try {
                metadata = OIDCProviderMetadata.resolve(
                        new Issuer(configuration.issuer().toString()),
                        5000, 10000);
            } catch (com.nimbusds.oauth2.sdk.GeneralException e) {
                throw new IOException("Unable to discover OIDC provider", e);
            }
        }
        return metadata;
    }
}
