package webpresenter.server.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Maps the Keycloak {@code realm_access.roles} claim to {@link Role} authorities, ignoring unknown roles. */
class KeycloakRealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .flatMap(name -> Role.fromKeycloakName(name).stream())
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
                .toList();
    }
}
