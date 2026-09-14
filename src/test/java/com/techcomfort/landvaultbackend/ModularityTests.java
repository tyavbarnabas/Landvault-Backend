package com.techcomfort.landvaultbackend;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

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
 * <p>
 * Note: a {@code Documenter}-based doc-generation test lived here too, but
 * {@code spring-modulith-docs} isn't on the classpath since
 * {@code spring-modulith-starter-test} was swapped for bare
 * {@code spring-modulith-core} in pom.xml — add it back (as a test-scope
 * dependency) if that's brought back.
 */
class ModularityTests {

    @Test
    void verifiesModularStructure() {
        ApplicationModules.of(LandvaultApplication.class).verify();
    }
}
