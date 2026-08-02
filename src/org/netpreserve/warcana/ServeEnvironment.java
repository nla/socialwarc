package org.netpreserve.warcana;

import org.netpreserve.warcana.web.OidcConfiguration;
import org.netpreserve.warcana.bamboo.BambooConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Arrays;

record ServeEnvironment(
        Path collection,
        String host,
        int port,
        URI publicUrl,
        URI oidcIssuer,
        String oidcClientId,
        String oidcClientSecret,
        String oidcManageRole,
        URI bambooUrl,
        Long bambooSeriesId,
        List<Long> bambooIndexSeriesIds) {
    static ServeEnvironment read(Map<String, String> environment)
            throws IOException {
        Path collection = path(environment.get("WARCANA_COLLECTION"));
        String host = value(environment, "WARCANA_HOST", "127.0.0.1");
        int port = integer(environment, "WARCANA_PORT", 8080);
        URI publicUrl = uri(environment.get("WARCANA_PUBLIC_URL"));

        String issuer = blankToNull(environment.get("WARCANA_OIDC_ISSUER"));
        String clientId = blankToNull(environment.get("WARCANA_OIDC_CLIENT_ID"));
        String directSecret = blankToNull(
                environment.get("WARCANA_OIDC_CLIENT_SECRET"));
        String secretFile = blankToNull(
                environment.get("WARCANA_OIDC_CLIENT_SECRET_FILE"));
        String role = value(
                environment, "WARCANA_OIDC_MANAGE_ROLE", "panadmin");
        URI bambooUrl = uri(environment.get("WARCANA_BAMBOO_URL"));
        Long bambooSeriesId = longValue(
                environment, "WARCANA_BAMBOO_SERIES_ID");
        List<Long> bambooIndexSeriesIds = longList(
                environment, "WARCANA_BAMBOO_INDEX_SERIES_IDS");
        boolean bambooRequested = bambooSeriesId != null ||
                !bambooIndexSeriesIds.isEmpty();
        if ((bambooUrl != null) != bambooRequested) {
            throw new IllegalArgumentException(
                    "WARCANA_BAMBOO_URL is required when Bamboo ingest or " +
                            "index series are configured");
        }

        boolean anyOidc = issuer != null || clientId != null ||
                directSecret != null || secretFile != null;
        if (anyOidc) {
            if (issuer == null || clientId == null) {
                throw new IllegalArgumentException(
                        "WARCANA_OIDC_ISSUER and WARCANA_OIDC_CLIENT_ID " +
                                "are required when OIDC is configured");
            }
            if (directSecret != null && secretFile != null) {
                throw new IllegalArgumentException(
                        "Set only one of WARCANA_OIDC_CLIENT_SECRET and " +
                                "WARCANA_OIDC_CLIENT_SECRET_FILE");
            }
            String secret = directSecret;
            if (secretFile != null) {
                secret = Files.readString(
                        Path.of(secretFile), StandardCharsets.UTF_8).strip();
            }
            return new ServeEnvironment(
                    collection, host, port, publicUrl,
                    URI.create(issuer), clientId, secret, role,
                    bambooUrl, bambooSeriesId, bambooIndexSeriesIds);
        }
        return new ServeEnvironment(
                collection, host, port, publicUrl, null, null, null, role,
                bambooUrl, bambooSeriesId, bambooIndexSeriesIds);

    }

    ServeEnvironment withCollection(Path value) {
        return new ServeEnvironment(
                value, host, port, publicUrl, oidcIssuer, oidcClientId,
                oidcClientSecret, oidcManageRole, bambooUrl, bambooSeriesId,
                bambooIndexSeriesIds);
    }

    ServeEnvironment withHost(String value) {
        return new ServeEnvironment(
                collection, value, port, publicUrl, oidcIssuer, oidcClientId,
                oidcClientSecret, oidcManageRole, bambooUrl, bambooSeriesId,
                bambooIndexSeriesIds);
    }

    ServeEnvironment withPort(int value) {
        return new ServeEnvironment(
                collection, host, value, publicUrl, oidcIssuer, oidcClientId,
                oidcClientSecret, oidcManageRole, bambooUrl, bambooSeriesId,
                bambooIndexSeriesIds);
    }

    ServeEnvironment withPublicUrl(URI value) {
        return new ServeEnvironment(
                collection, host, port, value, oidcIssuer, oidcClientId,
                oidcClientSecret, oidcManageRole, bambooUrl, bambooSeriesId,
                bambooIndexSeriesIds);
    }

    OidcConfiguration oidc() {
        return oidcIssuer == null ? null : new OidcConfiguration(
                oidcIssuer, oidcClientId, oidcClientSecret, publicUrl,
                oidcManageRole);
    }

    BambooConfiguration bamboo() {
        return bambooUrl == null ? null : new BambooConfiguration(
                bambooUrl, bambooSeriesId, bambooIndexSeriesIds, oidc());
    }

    private static String value(
            Map<String, String> environment, String name, String fallback) {
        String value = blankToNull(environment.get(name));
        return value == null ? fallback : value;
    }

    private static int integer(
            Map<String, String> environment, String name, int fallback) {
        String value = blankToNull(environment.get(name));
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static Long longValue(
            Map<String, String> environment, String name) {
        String value = blankToNull(environment.get(name));
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static List<Long> longList(
            Map<String, String> environment, String name) {
        String value = blankToNull(environment.get(name));
        if (value == null) {
            return List.of();
        }
        try {
            return Arrays.stream(value.split(","))
                    .map(String::strip)
                    .filter(part -> !part.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " must be a comma-separated list of integers");
        }
    }

    private static URI uri(String value) {
        value = blankToNull(value);
        return value == null ? null : URI.create(value);
    }

    private static Path path(String value) {
        value = blankToNull(value);
        return value == null ? null : Path.of(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
