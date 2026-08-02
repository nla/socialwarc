package org.netpreserve.warcana.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationResponseParser;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.token.OIDCTokens;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class OidcAuthenticator {
    private static final long PENDING_SECONDS = 10 * 60;
    private static final long CLOCK_SKEW_SECONDS = 30;

    record Login(URI location) {
    }

    record Session(String id, String username, Instant expiresAt) {
    }

    record Callback(Session session, String returnTo) {
    }

    private record Pending(
            State state,
            Nonce nonce,
            CodeVerifier verifier,
            String returnTo,
            Instant expiresAt) {
    }

    private final OidcConfiguration configuration;
    private final Issuer issuer;
    private final ClientID clientId;
    private final OIDCProviderMetadata metadata;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    OidcAuthenticator(OidcConfiguration configuration) throws IOException {
        this.configuration = configuration;
        issuer = new Issuer(configuration.issuer().toString());
        clientId = new ClientID(configuration.clientId());
        try {
            metadata = OIDCProviderMetadata.resolve(issuer, 5000, 10000);
        } catch (com.nimbusds.oauth2.sdk.GeneralException e) {
            throw new IOException("Unable to discover OIDC provider", e);
        }
        if (metadata.getAuthorizationEndpointURI() == null ||
                metadata.getTokenEndpointURI() == null ||
                metadata.getJWKSetURI() == null) {
            throw new IOException(
                    "OIDC discovery document is missing required endpoints");
        }
    }

    Login begin(String returnTo) {
        cleanup();
        State state = new State();
        Nonce nonce = new Nonce();
        CodeVerifier verifier = new CodeVerifier();
        pending.put(state.getValue(), new Pending(
                state, nonce, verifier, safeReturnTo(returnTo),
                Instant.now().plusSeconds(PENDING_SECONDS)));
        var request = new AuthenticationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                new Scope("openid"), clientId, configuration.callbackUrl())
                .endpointURI(metadata.getAuthorizationEndpointURI())
                .state(state)
                .nonce(nonce)
                .codeChallenge(verifier, CodeChallengeMethod.S256)
                .build();
        return new Login(request.toURI());
    }

    Callback complete(URI callbackRequest) throws IOException {
        cleanup();
        try {
            URI absolute = URI.create(configuration.callbackUrl() +
                    (callbackRequest.getRawQuery() == null
                            ? ""
                            : "?" + callbackRequest.getRawQuery()));
            AuthenticationResponse response =
                    AuthenticationResponseParser.parse(absolute);
            State returnedState = response.getState();
            Pending transaction = returnedState == null
                    ? null
                    : pending.remove(returnedState.getValue());
            if (transaction == null ||
                    transaction.expiresAt().isBefore(Instant.now())) {
                throw new IOException("OIDC login state is missing or expired");
            }
            if (!response.indicatesSuccess()) {
                throw new IOException("OIDC login failed: " +
                        response.toErrorResponse().getErrorObject());
            }
            AuthenticationSuccessResponse success =
                    response.toSuccessResponse();
            if (success.getAuthorizationCode() == null) {
                throw new IOException("OIDC provider returned no authorization code");
            }
            var authentication = new ClientSecretBasic(
                    clientId, new Secret(configuration.clientSecret()));
            var grant = new AuthorizationCodeGrant(
                    success.getAuthorizationCode(),
                    configuration.callbackUrl(), transaction.verifier());
            TokenResponse tokenResponse = OIDCTokenResponseParser.parse(
                    new TokenRequest(
                            metadata.getTokenEndpointURI(), authentication,
                            grant).toHTTPRequest().send());
            if (!tokenResponse.indicatesSuccess()) {
                throw new IOException("OIDC token exchange failed: " +
                        tokenResponse.toErrorResponse().getErrorObject());
            }
            OIDCTokens tokens = tokenResponse.toSuccessResponse()
                    .getTokens().toOIDCTokens();
            if (tokens.getIDToken() == null) {
                throw new IOException("OIDC provider returned no ID token");
            }
            validateIdToken(tokens, transaction.nonce());
            SignedJWT accessToken = SignedJWT.parse(
                    tokens.getAccessToken().getValue());
            var claims = accessToken.getJWTClaimsSet();
            requireIssuerAndClient(claims);
            if (!hasManageRole(claims.getClaim("realm_access"))) {
                throw new ForbiddenException(
                        "The required realm role is missing: " +
                                configuration.manageRole());
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null) {
                throw new IOException("OIDC access token has no expiration");
            }
            String username = firstNonBlank(
                    claims.getStringClaim("preferred_username"),
                    claims.getSubject());
            Session session = new Session(
                    UUID.randomUUID().toString(), username,
                    expiration.toInstant());
            sessions.put(session.id(), session);
            return new Callback(session, transaction.returnTo());
        } catch (com.nimbusds.oauth2.sdk.ParseException |
                 com.nimbusds.jose.JOSEException |
                 com.nimbusds.jose.proc.BadJOSEException |
                 ParseException e) {
            throw new IOException("Invalid OIDC response", e);
        }
    }

    Session session(String id) {
        if (id == null) {
            return null;
        }
        Session session = sessions.get(id);
        if (session != null && session.expiresAt().isAfter(Instant.now())) {
            return session;
        }
        sessions.remove(id);
        return null;
    }

    void logout(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    private void validateIdToken(OIDCTokens tokens, Nonce nonce)
            throws com.nimbusds.jose.JOSEException,
            com.nimbusds.jose.proc.BadJOSEException, IOException {
        JWSAlgorithm algorithm = tokens.getIDToken().getHeader()
                .getAlgorithm() instanceof JWSAlgorithm value ? value : null;
        if (algorithm == null || JWSAlgorithm.NONE.equals(algorithm) ||
                metadata.getIDTokenJWSAlgs() == null ||
                !metadata.getIDTokenJWSAlgs().contains(algorithm)) {
            throw new IOException("OIDC ID token uses an unsupported algorithm");
        }
        var validator = new IDTokenValidator(
                issuer, clientId, algorithm, metadata.getJWKSetURI().toURL());
        validator.setMaxClockSkew((int) CLOCK_SKEW_SECONDS);
        validator.validate(tokens.getIDToken(), nonce);
    }

    private void requireIssuerAndClient(com.nimbusds.jwt.JWTClaimsSet claims)
            throws IOException, ParseException {
        if (!configuration.issuer().toString().equals(claims.getIssuer())) {
            throw new IOException("OIDC access token issuer does not match");
        }
        Date expiration = claims.getExpirationTime();
        if (expiration == null || expiration.toInstant().plusSeconds(
                CLOCK_SKEW_SECONDS).isBefore(Instant.now())) {
            throw new IOException("OIDC access token is expired");
        }
        String authorisedParty = claims.getStringClaim("azp");
        if (!configuration.clientId().equals(authorisedParty) &&
                !claims.getAudience().contains(configuration.clientId())) {
            throw new IOException("OIDC access token was issued to another client");
        }
    }

    private boolean hasManageRole(Object realmAccess) {
        if (!(realmAccess instanceof Map<?, ?> values)) {
            return false;
        }
        Object roles = values.get("roles");
        if (!(roles instanceof Collection<?> collection)) {
            return false;
        }
        return collection.stream().anyMatch(role ->
                configuration.manageRole().equalsIgnoreCase(String.valueOf(role)));
    }

    private void cleanup() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry ->
                entry.getValue().expiresAt().isBefore(now));
        sessions.entrySet().removeIf(entry ->
                entry.getValue().expiresAt().isBefore(now));
    }

    private static String safeReturnTo(String value) {
        if (value == null || !value.startsWith("/manage") ||
                value.startsWith("//")) {
            return "/manage";
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    static class ForbiddenException extends IOException {
        ForbiddenException(String message) {
            super(message);
        }
    }
}
