package com.techcomfort.landvaultbackend.common;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Issues {@code SET LOCAL landvault.*} statements the instant a connection
 * enters a real transaction, reading whatever {@link TenantContext} holds
 * at that moment. See AGENTS.md for why this — a {@code DataSource}
 * decorator — was chosen over a Hibernate {@code StatementInspector} or a
 * {@code TransactionSynchronization}.
 * <p>
 * <b>Why {@code SET LOCAL} and never plain {@code SET}:</b> {@code SET LOCAL}
 * scopes the value to the current transaction only — when the connection
 * returns to the pool, it carries nothing. Plain {@code SET} persists on the
 * physical connection itself, so the next tenant to borrow that pooled
 * connection would inherit the previous tenant's session variables. Same
 * class of bug as a missing {@link TenantContext#clear()}, one layer lower
 * and much harder to notice, because it depends on which physical
 * connection the pool happens to hand out.
 * <p>
 * <b>Why a {@code Connection} proxy, not just {@link #getConnection()}.</b>
 * Two earlier versions of this class got this wrong, in increasingly subtle
 * ways worth recording so they aren't retried:
 * <ol>
 *   <li>Gating on {@code TransactionSynchronizationManager.isActualTransactionActive()}
 *       inside {@code getConnection()}: that flag is only flipped {@code true}
 *       in Spring's {@code prepareSynchronization()}, which runs <i>after</i>
 *       {@code JpaTransactionManager.doBegin()} returns — but the physical
 *       connection is acquired <i>inside</i> {@code doBegin()}. The check
 *       was always false at exactly the point this class could act.</li>
 *   <li>Dropping that check and running {@code SET LOCAL} unconditionally
 *       in {@code getConnection()}, trusting Postgres to no-op it outside a
 *       real transaction: {@code getConnection()} fires before the caller
 *       has done <i>anything</i> to the connection, including flipping
 *       {@code setAutoCommit(false)}. So {@code SET LOCAL} always ran while
 *       the connection was still in the pool's default autocommit=true
 *       state. Postgres doesn't quietly ignore that: it emits
 *       {@code WARNING: SET LOCAL can only be used in transaction blocks}
 *       <i>and</i> registers the custom GUC as an empty-string placeholder
 *       from then on — worse than a no-op, because every later
 *       {@code current_setting(..., true)} read on that connection then
 *       returns {@code ''} instead of either the real value or {@code NULL},
 *       which is indistinguishable from this class's own deliberate
 *       "authenticated, no tenant" empty-string convention (see below).
 *       Confirmed directly against Postgres, not guessed.</li>
 * </ol>
 * The fix is to stop guessing when the caller is "probably" about to start
 * a real transaction, and instead react to the one call that unambiguously
 * means one has: {@code Connection.setAutoCommit(false)}. This class wraps
 * every connection it hands out in a dynamic proxy that watches for exactly
 * that transition and issues the {@code SET LOCAL} statements at that exact
 * moment — guaranteed to be inside the transaction block Postgres requires,
 * whatever ordering the JPA/Hibernate/Spring stack happens to use.
 * <p>
 * <b>Null handling is deliberate and three-way</b> — see AGENTS.md:
 * <ul>
 *   <li>{@link TenantContext} empty (system/background work, or a public
 *       endpoint's transactional method reached with nothing authenticated)
 *       → nothing is set at all. {@code current_setting('landvault.tenant_id', true)}
 *       returns SQL {@code NULL}: "no context was ever established here."</li>
 *   <li>A scope is present but its {@code tenantId}/{@code branchId} is null
 *       (a buyer, platform staff, an org-wide role) → explicitly set to the
 *       empty string, not left unset. {@code current_setting(..., true)}
 *       returns {@code ''}: "a request WAS authenticated, it genuinely has
 *       no tenant/branch." A future RLS policy needs to tell these two
 *       apart — unset should default-deny, empty-string-for-a-buyer should
 *       not be treated as a missing-context bug.</li>
 *   <li>A real UUID → set as that UUID's string form.</li>
 * </ul>
 * The three values interpolated into the {@code SET LOCAL} text are always
 * either a {@link UUID}'s own {@code toString()} (fixed, punctuation-only
 * format — never attacker-controlled text) or one of the two Java string
 * literals {@code "on"}/{@code "off"}, never a request header or other raw
 * input — {@code SET} doesn't support JDBC bind-parameter placeholders for
 * its value position at all, so this interpolation is deliberate, not an
 * oversight, and is safe only because nothing here ever originates as
 * arbitrary text.
 */
public class TenantScopedDataSource extends DelegatingDataSource implements Closeable {

    public TenantScopedDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private static Connection wrap(Connection target) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                Object result = method.invoke(target, args);
                if (isEnablingARealTransaction(method, args)) {
                    applyTenantScope(target);
                }
                return result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                TenantScopedDataSource.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    private static boolean isEnablingARealTransaction(Method method, Object[] args) {
        return "setAutoCommit".equals(method.getName()) && args.length == 1 && Boolean.FALSE.equals(args[0]);
    }

    private static void applyTenantScope(Connection connection) throws SQLException {
        Optional<TenantScope> maybeScope = TenantContext.get();
        if (maybeScope.isEmpty()) {
            // See the class Javadoc's null-handling note: deliberately left
            // fully unset, not set to a default, so current_setting(..., true)
            // can tell "no context" apart from "authenticated, no tenant."
            // Also correctly skips Liquibase's own connections and any
            // other non-request JDBC use, since TenantContext is only ever
            // set inside TenantContextFilter's request handling.
            return;
        }

        TenantScope scope = maybeScope.get();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL landvault.tenant_id = '" + literal(scope.tenantId()) + "'");
            statement.execute("SET LOCAL landvault.branch_id = '" + literal(scope.branchId()) + "'");
            statement.execute("SET LOCAL landvault.platform_scope = " + (scope.platformStaff() ? "on" : "off"));
        }
    }

    private static String literal(UUID id) {
        return id == null ? "" : id.toString();
    }

    // DelegatingDataSource isn't itself Closeable, so without this override
    // wrapping the auto-configured HikariDataSource bean would silently stop
    // Spring's shutdown hook from ever closing the underlying pool.
    @Override
    public void close() {
        if (getTargetDataSource() instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
