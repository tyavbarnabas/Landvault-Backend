package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.LandvaultApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Enabled but blank" must fail startup outright, not silently do nothing
 * — see AGENTS.md. Asserting a full application context fails to refresh
 * doesn't fit {@code @SpringBootTest} cleanly (there's no context left to
 * inject anything into, and JUnit would just report the test itself as
 * broken), so this drives {@link SpringApplicationBuilder} directly instead
 * — the same entry point {@code LandvaultApplication.main} uses, just with
 * properties supplied programmatically rather than from {@code application.yml}.
 * <p>
 * Properties are passed as {@code --key=value} command-line-style
 * arguments to {@code run(String...)}, not via
 * {@code SpringApplicationBuilder.properties(Map)} — that method adds to
 * Boot's lowest-precedence "default properties" source, which
 * {@code application.yml}'s own placeholder-defaulted values (e.g.
 * {@code ${DB_HOST:localhost}}) still win over. The first version of this
 * test used {@code .properties(...)} and every override was silently
 * ignored — it connected to whatever Postgres {@code application.yml}'s
 * own defaults pointed at instead of this container, and both assertions
 * failed with "Expecting code to raise a throwable" because bootstrap saw
 * its real (disabled-by-default) config and did nothing. Command-line
 * arguments outrank {@code application.yml}, so they actually override it.
 */
@Testcontainers
class SuperAdminBootstrapMisconfiguredIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private ConfigurableApplicationContext context;

    @AfterEach
    void closeContextIfItStarted() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void startupFailsWhenEnabledWithABlankPassword() {
        List<String> args = new ArrayList<>(baseArgs());
        args.add("--landvault.bootstrap.super-admin.enabled=true");
        args.add("--landvault.bootstrap.super-admin.email=admin@example.com");
        args.add("--landvault.bootstrap.super-admin.password=");

        assertThatThrownBy(() -> context = new SpringApplicationBuilder(LandvaultApplication.class)
                .run(args.toArray(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("email/password is blank");
    }

    @Test
    void startupFailsWhenEnabledWithABlankEmail() {
        List<String> args = new ArrayList<>(baseArgs());
        args.add("--landvault.bootstrap.super-admin.enabled=true");
        args.add("--landvault.bootstrap.super-admin.email=");
        args.add("--landvault.bootstrap.super-admin.password=correct horse battery staple");

        assertThatThrownBy(() -> context = new SpringApplicationBuilder(LandvaultApplication.class)
                .run(args.toArray(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("email/password is blank");
    }

    private static List<String> baseArgs() {
        return List.of(
                "--server.port=0",
                "--app.jwt.secret=integration-test-signing-secret-of-at-least-32-bytes",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.liquibase.url=" + POSTGRES.getJdbcUrl(),
                "--spring.liquibase.user=" + POSTGRES.getUsername(),
                "--spring.liquibase.password=" + POSTGRES.getPassword(),
                // Changeset 019 needs these to create the restricted app
                // role — its own value is irrelevant here since this test
                // never gets far enough to care which role the app itself
                // connects as.
                "--spring.liquibase.parameters.appDbUsername=landvault_app_misconfigured_test",
                "--spring.liquibase.parameters.appDbPassword=unused-test-password");
    }
}
