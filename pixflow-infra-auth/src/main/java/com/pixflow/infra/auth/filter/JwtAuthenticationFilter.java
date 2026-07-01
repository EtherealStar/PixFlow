package com.pixflow.infra.auth.filter;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
                        principal.authorities().stream().map(SimpleGrantedAuthority::new).toList());
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
        String fallback = request.getHeader(X_AUTH_TOKEN);
        return StringUtils.hasText(fallback) ? fallback.trim() : null;
    }
}
