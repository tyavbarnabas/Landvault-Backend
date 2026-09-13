package com.techcomfort.landvaultbackend;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies the module structure declared via each module's
 * {@code package-info.java} actually holds — no module reaching into
 * another's {@code internal} package, no module exposing an internal type
 * through its public API.
 * <p>
 * Deliberately <b>not</b> a {@code @SpringBootTest}:
 * {@link ApplicationModules#verify()} works from the compiled package
 * structure alone, so this must run fast, without a Spring context and
 * without a database/Docker.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(LandvaultApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
