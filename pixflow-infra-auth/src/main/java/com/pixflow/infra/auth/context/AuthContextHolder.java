package com.pixflow.infra.auth.context;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthContextHolder {
    private AuthContextHolder() {
    }

    public static Optional<AuthPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static AuthPrincipal requireCurrent() {
        return current().orElseThrow(
                () -> new IllegalStateException("No authenticated PixFlow user in current context"));
    }
}
