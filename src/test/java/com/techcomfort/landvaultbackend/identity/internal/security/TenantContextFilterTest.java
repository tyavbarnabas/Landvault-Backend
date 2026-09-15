package com.techcomfort.landvaultbackend.identity.internal.security;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The regression test for the worst bug this filter can produce: the
 * ThreadLocal must never survive past the end of a request, including one
 * that fails. See TenantContext's own Javadoc.
 */
@ExtendWith(MockitoExtension.class)
class TenantContextFilterTest {

    @Mock
    private TenancyApi tenancyApi;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    @AfterEach
    void resetGlobalState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void clearsTenantContextEvenWhenTheChainThrows() throws Exception {
        AccessTokenClaims claims = new AccessTokenClaims(UUID.randomUUID(), "user@example.com", null, false, List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, List.of()));
        doThrow(new RuntimeException("downstream failure")).when(chain).doFilter(request, response);

        TenantContextFilter filter = new TenantContextFilter(tenancyApi);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("downstream failure");

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void setsNoScopeForAnUnauthenticatedRequestAndStillCallsTheChain() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(tenancyApi);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(TenantContext.get()).isEmpty();
    }
}
