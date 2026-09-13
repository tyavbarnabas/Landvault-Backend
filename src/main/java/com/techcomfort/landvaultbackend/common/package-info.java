/**
 * Shared, cross-cutting code used by every application module: the base JPA
 * entity behaviour ({@link com.techcomfort.landvaultbackend.common.AbstractEntity}),
 * enums referenced across module boundaries (e.g.
 * {@link com.techcomfort.landvaultbackend.common.Currency}), and
 * infrastructure configuration (JPA auditing, etc.).
 * <p>
 * Declared {@link org.springframework.modulith.ApplicationModule.Type#OPEN}
 * because every other module depends on it — that's the point of a shared
 * kernel, not a boundary violation. In exchange, keep this package strictly
 * free of domain/business logic: nothing here should encode a rule that
 * belongs to a specific module (identity, tenancy, ...).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Common",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.techcomfort.landvaultbackend.common;
