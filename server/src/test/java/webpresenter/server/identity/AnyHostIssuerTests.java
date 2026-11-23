package webpresenter.server.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AnyHostIssuerTests {

    private final org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> validator = AnyHostJwtDecoderConfig.issuerPath("/auth/realms/webpresenter");

    private static Jwt token(String issuer) {
        return Jwt.withTokenValue("t").header("alg", "RS256").issuer(issuer).subject("x")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    void acceptsTheRealmOnAnyAddress() {
        assertThat(validator.validate(token("https://webpresenter.local:8800/auth/realms/webpresenter")).hasErrors()).isFalse();
        assertThat(validator.validate(token("https://192.168.18.22:8800/auth/realms/webpresenter")).hasErrors()).isFalse();
    }

    @Test
    void rejectsOtherRealmsAndOddIssuers() {
        assertThat(validator.validate(token("https://webpresenter.local/auth/realms/master")).hasErrors()).isTrue();
        assertThat(validator.validate(token("ftp://x/auth/realms/webpresenter")).hasErrors()).isTrue();
    }
}
