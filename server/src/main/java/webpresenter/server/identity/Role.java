package webpresenter.server.identity;

import java.util.Arrays;
import java.util.Optional;

/** Fixed global roles, defined as realm roles in Keycloak. */
public enum Role {
    ADMIN("admin"),
    AV_TECHNICIAN("av-technician"),
    WORSHIP_LEADER("worship-leader"),
    MUSICIAN("musician");

    private final String keycloakName;

    Role(String keycloakName) {
        this.keycloakName = keycloakName;
    }

    /** Name of the realm role in Keycloak. */
    public String keycloakName() {
        return keycloakName;
    }

    /** Spring Security authority, usable as {@code hasRole("AV_TECHNICIAN")}. */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromKeycloakName(String name) {
        return Arrays.stream(values()).filter(r -> r.keycloakName.equals(name)).findFirst();
    }
}
