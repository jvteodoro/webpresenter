package webpresenter.server.identity;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CurrentUserController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class CurrentUserControllerTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void rejectsRequestsWithoutToken() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void mapsKeycloakTokenToCurrentUser() throws Exception {
        // Goes through the real filter chain and converter: only token decoding is stubbed.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("6f1c2a52-0000-4000-8000-000000000001")
                .claim("preferred_username", "lider")
                .claim("name", "Líder de Louvor")
                .claim("realm_access", Map.of("roles", List.of("worship-leader", "musician", "offline_access")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("token")).thenReturn(jwt);

        mvc.perform(get("/api/me").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("lider"))
                .andExpect(jsonPath("$.displayName").value("Líder de Louvor"))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("MUSICIAN", "WORSHIP_LEADER")));
    }
}
