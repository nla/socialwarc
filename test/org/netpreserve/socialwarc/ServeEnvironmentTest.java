package org.netpreserve.socialwarc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServeEnvironmentTest {
    @Test
    void appliesEnvironmentDefaultsAndOptionalOidc() throws Exception {
        ServeEnvironment defaults = ServeEnvironment.read(Map.of());
        assertEquals("127.0.0.1", defaults.host());
        assertEquals(8080, defaults.port());
        assertNull(defaults.oidc());

        ServeEnvironment configured = ServeEnvironment.read(Map.of(
                "SOCIALWARC_COLLECTION", "/collections/social",
                "SOCIALWARC_HOST", "0.0.0.0",
                "SOCIALWARC_PORT", "9090",
                "SOCIALWARC_PUBLIC_URL", "https://socialwarc.example/",
                "SOCIALWARC_OIDC_ISSUER", "https://keycloak.example/realms/nla/",
                "SOCIALWARC_OIDC_CLIENT_ID", "socialwarc",
                "SOCIALWARC_OIDC_CLIENT_SECRET", "secret"));
        assertEquals("0.0.0.0", configured.host());
        assertEquals(9090, configured.port());
        assertEquals("https://keycloak.example/realms/nla",
                configured.oidc().issuer().toString());
        assertEquals("https://socialwarc.example/oidc/callback",
                configured.oidc().callbackUrl().toString());
        assertEquals("panadmin", configured.oidc().manageRole());

        assertThrows(IllegalArgumentException.class, () ->
                configured.withPublicUrl(
                        java.net.URI.create("https://socialwarc.example/base"))
                        .oidc());
    }

    @Test
    void readsSecretFileAndRejectsAmbiguousSecrets() throws Exception {
        var secret = Files.createTempFile("socialwarc-oidc-", ".secret");
        Files.writeString(secret, "mounted-secret\n");
        ServeEnvironment configured = ServeEnvironment.read(Map.of(
                "SOCIALWARC_PUBLIC_URL", "https://socialwarc.example/",
                "SOCIALWARC_OIDC_ISSUER", "https://keycloak.example/realm/nla",
                "SOCIALWARC_OIDC_CLIENT_ID", "socialwarc",
                "SOCIALWARC_OIDC_CLIENT_SECRET_FILE", secret.toString()));
        assertEquals("mounted-secret",
                configured.oidc().clientSecret());

        assertThrows(IllegalArgumentException.class, () ->
                ServeEnvironment.read(Map.of(
                        "SOCIALWARC_PUBLIC_URL", "https://socialwarc.example/",
                        "SOCIALWARC_OIDC_ISSUER", "https://keycloak.example/realm/nla",
                        "SOCIALWARC_OIDC_CLIENT_ID", "socialwarc",
                        "SOCIALWARC_OIDC_CLIENT_SECRET", "one",
                        "SOCIALWARC_OIDC_CLIENT_SECRET_FILE", secret.toString())));
    }
}
