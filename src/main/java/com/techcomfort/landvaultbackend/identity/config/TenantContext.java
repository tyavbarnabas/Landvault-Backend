package com.techcomfort.landvaultbackend.identity.config;

public class TenantContext {

    private static  final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public String getCurrentTenant(final String tenant) {
        CURRENT_TENANT.set(tenant);
        return CURRENT_TENANT.get();
    }
}
