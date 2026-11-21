package webpresenter.server;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Verifies module boundaries (no cycles, no access to other modules' internals) and documents them. */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(WebpresenterServer.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
