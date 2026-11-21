package webpresenter.server.identity;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the authenticated user, so the frontend can adapt to the user's roles. */
@RestController
class CurrentUserController {

    @GetMapping("/api/me")
    CurrentUser me(JwtAuthenticationToken authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new CurrentUser(
                authentication.getName(),
                authentication.getToken().getClaimAsString("name"),
                roles);
    }

    record CurrentUser(String username, String displayName, List<String> roles) {}
}
