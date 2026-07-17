package com.pixflow.infra.auth.filter;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String BEARER_PREFIX = "Bearer ";

    public static final String AUTHORIZATION = "Authorization";

    public static final String X_AUTH_TOKEN = "X-Auth-Token";

    private final AuthService authService;

    private final SecurityErrorWriter errorWriter;

    public JwtAuthenticationFilter(AuthService authService, SecurityErrorWriter errorWriter) {
        this.authService = authService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = servletPath(request);
        return isAnonymousAuthEndpoint(request, path)
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || path.equals("/actuator/health")
                || path.equals("/ws")
                || path.startsWith("/ws/")
                || path.equals("/")
                || path.equals("/index.html")
                || path.startsWith("/assets/")
                || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token)) {
                AuthPrincipal principal = authService.authenticateAccessToken(token);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        token,
                        java.util.List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } catch (AuthException ex) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, ex);
        }
    }

    public static String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        if (StringUtils.hasText(authorization)) {
            return null;
        }
        String fallback = request.getHeader(X_AUTH_TOKEN);
        return StringUtils.hasText(fallback) ? fallback.trim() : null;
    }

    private static boolean isAnonymousAuthEndpoint(HttpServletRequest request, String path) {
        return HttpMethod.POST.matches(request.getMethod())
                && (path.equals("/api/auth/login")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout"));
    }

    private static String servletPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? "/" : path;
    }
}
