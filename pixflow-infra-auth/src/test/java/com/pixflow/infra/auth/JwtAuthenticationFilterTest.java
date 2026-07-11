package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.auth.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class JwtAuthenticationFilterTest {
    private final TestJwtAuthenticationFilter filter = new TestJwtAuthenticationFilter();

    @Test
    void skipsPublicAuthPaths() {
        assertThat(filter.shouldSkip(request("POST", "/api/auth/login"))).isTrue();
        assertThat(filter.shouldSkip(request("POST", "/api/auth/register"))).isTrue();
        assertThat(filter.shouldSkip(request("POST", "/api/auth/refresh"))).isTrue();
    }

    @Test
    void filtersAuthenticatedAuthPaths() {
        assertThat(filter.shouldSkip(request("GET", "/api/auth/me"))).isFalse();
        assertThat(filter.shouldSkip(request("POST", "/api/auth/logout"))).isFalse();
    }

    @Test
    void stillFiltersBusinessApiPaths() {
        assertThat(filter.shouldSkip(request("GET", "/api/conversations"))).isFalse();
        assertThat(filter.shouldSkip(request("GET", "/api/files/packages"))).isFalse();
    }

    @Test
    void matchesServletPathUnderContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pixflow/api/auth/login");
        request.setContextPath("/pixflow");
        request.setServletPath("/api/auth/login");

        assertThat(filter.shouldSkip(request)).isTrue();
    }

    @Test
    void skipsContainerAsyncAndErrorDispatches() {
        assertThat(filter.skipAsyncDispatch()).isTrue();
        assertThat(filter.skipErrorDispatch()).isTrue();
    }

    @Test
    void authorizationHeaderTakesPrecedenceOverFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader(JwtAuthenticationFilter.AUTHORIZATION, "Basic abc");
        request.addHeader(JwtAuthenticationFilter.X_AUTH_TOKEN, "fallback-token");

        assertThat(JwtAuthenticationFilter.extractToken(request)).isNull();
    }

    private static HttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private static final class TestJwtAuthenticationFilter extends JwtAuthenticationFilter {
        private TestJwtAuthenticationFilter() {
            super(null, null);
        }

        private boolean shouldSkip(HttpServletRequest request) {
            return shouldNotFilter(request);
        }

        private boolean skipAsyncDispatch() {
            return shouldNotFilterAsyncDispatch();
        }

        private boolean skipErrorDispatch() {
            return shouldNotFilterErrorDispatch();
        }
    }
}
