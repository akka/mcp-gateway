package io.akka.mcp.gateway.api;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URL;
import java.util.Optional;

/**
 * Verifies Okta-issued JWT access tokens against the Okta JWKS.
 *
 * The Akka SDK {@code @JWT} annotation gates the endpoint by issuer match,
 * but signature verification against a remote JWKS is not handled out of the
 * box for local development — so we do it here with nimbus-jose-jwt.
 */
public final class OktaJwtVerifier {

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final String expectedIssuer;

    public OktaJwtVerifier(String issuer, String jwksUri) {
        this.expectedIssuer = issuer;
        try {
            JWKSource<SecurityContext> keySource = JWKSourceBuilder
                    .create(new URL(jwksUri))
                    .retrying(true)
                    .build();
            JWSKeySelector<SecurityContext> keySelector =
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
            ConfigurableJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
            p.setJWSKeySelector(keySelector);

            // Accept typ headers Okta and OIDC providers use:
            //   "JWT"                               (legacy / ID tokens)
            //   "at+jwt"                            (RFC 9068 OAuth 2.0 access tokens)
            //   "application/okta-internal-at+jwt"  (Okta's proprietary access-token typ)
            p.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                    JOSEObjectType.JWT,
                    new JOSEObjectType("at+jwt"),
                    new JOSEObjectType("application/okta-internal-at+jwt")));

            this.processor = p;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise OktaJwtVerifier", e);
        }
    }

    public JWTClaimsSet verify(String bearerToken) throws Exception {
        JWTClaimsSet claims = processor.process(bearerToken, null);
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new SecurityException(
                    "Issuer mismatch: expected " + expectedIssuer + ", got " + claims.getIssuer());
        }
        return claims;
    }

    public static Optional<String> bearerTokenOf(String authorizationHeader) {
        if (authorizationHeader == null) return Optional.empty();
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        return Optional.of(authorizationHeader.substring(prefix.length()).trim());
    }

    public static String safeString(JWTClaimsSet claims, String name) {
        try {
            Object v = claims.getClaim(name);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String subject(JWTClaimsSet claims) {
        return claims.getSubject();
    }
}
