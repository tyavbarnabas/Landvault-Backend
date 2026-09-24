package com.techcomfort.landvaultbackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The context starts, and every Liquibase changeset applies cleanly to an
 * empty database.
 * <p>
 * <strong>The container is not optional.</strong> Without it this is a bare
 * {@code @SpringBootTest}, which resolves {@code spring.datasource.*} to
 * whatever the developer's own configuration points at — so
 * {@code ./mvnw verify} silently migrates their real local database. That is
 * how an unreleased changeset reached a live database once already: the
 * migration was applied by a test run, not by anyone starting the app, and
 * editing the file afterwards then failed checksum validation on a database
 * nobody expected it to have touched.
 * <p>
 * Keeping it here also makes this the cheapest real migration test in the
 * suite: a fresh container means the whole changelog runs from nothing on
 * every build, so a changeset that only works against an already-migrated
 * database fails immediately.
 */
@SpringBootTest
@Testcontainers
class LandvaultApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @Test
    void contextLoads() {
    }

}
