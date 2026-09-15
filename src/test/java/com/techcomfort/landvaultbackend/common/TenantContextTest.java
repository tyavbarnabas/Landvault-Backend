package com.techcomfort.landvaultbackend.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void isEmptyByDefault() {
        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void getReturnsWhatWasSet() {
        TenantScope scope = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), null, false);

        TenantContext.set(scope);

        assertThat(TenantContext.get()).contains(scope);
    }

    @Test
    void clearRemovesTheScope() {
        TenantContext.set(new TenantScope(UUID.randomUUID(), UUID.randomUUID(), null, false));

        TenantContext.clear();

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void clearIsSafeWhenNothingWasEverSet() {
        TenantContext.clear();

        assertThat(TenantContext.get()).isEmpty();
    }
}
