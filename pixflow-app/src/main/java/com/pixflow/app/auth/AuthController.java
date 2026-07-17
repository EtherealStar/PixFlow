package com.pixflow.app.auth;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.infra.auth.filter.JwtAuthenticationFilter;
import com.pixflow.infra.auth.service.AuthService;
import com.pixflow.infra.auth.service.AuthTokenResponse;
import com.pixflow.infra.auth.service.LoginRequest;
import com.pixflow.infra.auth.service.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    private final AuthProperties properties;

    public AuthController(AuthService authService, AuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenPayload> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        AuthTokenResponse tokenResponse = authService.login(request, clientIp(servletRequest));
        setRefreshCookie(response, tokenResponse);
        return ApiResponse.ok(AuthTokenPayload.from(tokenResponse));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokenPayload> refresh(
            @CookieValue(
                    name = "${pixflow.auth.refresh.cookie-name:PIXFLOW_REFRESH}",
                    required = false) String refreshToken,
            HttpServletResponse response) {
        AuthTokenResponse tokenResponse = authService.refresh(refreshToken);
        setRefreshCookie(response, tokenResponse);
        return ApiResponse.ok(AuthTokenPayload.from(tokenResponse));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @CookieValue(
                    name = "${pixflow.auth.refresh.cookie-name:PIXFLOW_REFRESH}",
                    required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        authService.logout(refreshToken, JwtAuthenticationFilter.extractToken(request));
        clearRefreshCookie(response);
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@CurrentUser AuthPrincipal principal) {
        return ApiResponse.ok(UserView.from(principal));
    }

    private void setRefreshCookie(HttpServletResponse response, AuthTokenResponse tokenResponse) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(tokenResponse.refreshToken())
                .maxAge(properties.getRefresh().getTtl())
                .build()
                .toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie("")
                .maxAge(0)
                .build()
                .toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(properties.getRefresh().getCookieName(), value)
                .httpOnly(true)
                .secure(properties.getRefresh().isCookieSecure())
                .sameSite(properties.getRefresh().getCookieSameSite())
                .path(properties.getRefresh().getCookiePath());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
