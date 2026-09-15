package com.techcomfort.landvaultbackend.common;

import java.util.Optional;

/**
 * Carries the current request's {@link TenantScope} from the servlet filter
 * that establishes it (identity's {@code TenantContextFilter}) to wherever
 * it's needed — a repository, a {@code DataSource} decorator setting
 * Postgres session variables — without threading it through every method
 * signature in between. Lives in {@code common} (not {@code identity})
 * because {@code tenancy}, and every future module that reads or writes
 * tenant-scoped data, needs it too.
 * <p>
 * A previous version of this class had a getter that also set, no
 * {@code clear()}, and static state hidden behind instance methods — this
 * rebuild is deliberately a plain, static, immutable-value {@code ThreadLocal}
 * holder instead: {@link #set}, {@link #get}, {@link #clear} and nothing
 * else. The record it holds has no setters of its own, so a scope can't be
 * mutated in place once established.
 * <p>
 * <b>{@link #clear()} is not optional.</b> The servlet container pools and
 * reuses threads: if a request finishes without clearing this ThreadLocal,
 * the next request handled by that same pooled thread — for a completely
 * different tenant — inherits the previous request's scope. That is the
 * single worst bug this architecture can produce (a live cross-tenant data
 * leak), and it is silent: nothing throws, nothing logs, the wrong tenant's
 * data just comes back. The only thing preventing it is every caller of
 * {@link #set} clearing in a {@code finally} block, unconditionally,
 * including on every exception path — see {@code TenantContextFilter}.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantScope scope) {
        CURRENT.set(scope);
    }

    public static Optional<TenantScope> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Must be called from a {@code finally} block by whoever called
     * {@link #set} — see the class Javadoc for why. Safe to call when
     * nothing was ever set.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
