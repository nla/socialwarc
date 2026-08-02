package org.netpreserve.warcana;

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
                "WARCANA_COLLECTION", "/collections/social",
                "WARCANA_HOST", "0.0.0.0",
                "WARCANA_PORT", "9090",
                "WARCANA_PUBLIC_URL", "https://warcana.example/",
                "WARCANA_OIDC_ISSUER", "https://keycloak.example/realms/nla/",
                "WARCANA_OIDC_CLIENT_ID", "warcana",
                "WARCANA_OIDC_CLIENT_SECRET", "secret"));
        assertEquals("0.0.0.0", configured.host());
        assertEquals(9090, configured.port());
        assertEquals("https://keycloak.example/realms/nla",
                configured.oidc().issuer().toString());
        assertEquals("https://warcana.example/oidc/callback",
                configured.oidc().callbackUrl().toString());
        assertEquals("panadmin", configured.oidc().manageRole());

        assertThrows(IllegalArgumentException.class, () ->
                configured.withPublicUrl(
                        java.net.URI.create("https://warcana.example/base"))
                        .oidc());
    }

    @Test
    void readsSecretFileAndRejectsAmbiguousSecrets() throws Exception {
        var secret = Files.createTempFile("warcana-oidc-", ".secret");
        Files.writeString(secret, "mounted-secret\n");
        ServeEnvironment configured = ServeEnvironment.read(Map.of(
                "WARCANA_PUBLIC_URL", "https://warcana.example/",
                "WARCANA_OIDC_ISSUER", "https://keycloak.example/realm/nla",
                "WARCANA_OIDC_CLIENT_ID", "warcana",
                "WARCANA_OIDC_CLIENT_SECRET_FILE", secret.toString()));
        assertEquals("mounted-secret",
                configured.oidc().clientSecret());

        assertThrows(IllegalArgumentException.class, () ->
                ServeEnvironment.read(Map.of(
                        "WARCANA_PUBLIC_URL", "https://warcana.example/",
                        "WARCANA_OIDC_ISSUER", "https://keycloak.example/realm/nla",
                        "WARCANA_OIDC_CLIENT_ID", "warcana",
                        "WARCANA_OIDC_CLIENT_SECRET", "one",
                        "WARCANA_OIDC_CLIENT_SECRET_FILE", secret.toString())));
    }
}
