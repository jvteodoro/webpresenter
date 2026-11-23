package webpresenter.server.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Production on a LAN whose address changes: Keycloak names itself after the address the browser
 * used (an IP, webpresenter.local …), so tokens carry that address in their issuer. They are
 * accepted when the issuer is our realm's path on any host; the signature, checked against the keys
 * fetched over the internal network, is what proves they are ours.
 */
@Configuration
@ConditionalOnProperty("webpresenter.oidc.issuer-path")
class AnyHostJwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(@Value("${webpresenter.oidc.jwk-set-uri}") String jwkSetUri,
            @Value("${webpresenter.oidc.issuer-path}") String issuerPath) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuerPath(issuerPath)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> issuerPath(String path) {
        return jwt -> {
            String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
            boolean ok;
            try {
                java.net.URI uri = java.net.URI.create(issuer);
                ok = ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && path.equals(uri.getPath());
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "unexpected issuer " + issuer, null));
        };
    }
}
