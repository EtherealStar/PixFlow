package com.pixflow.infra.auth.config;

import com.pixflow.infra.auth.identity.UsernameNormalizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "pixflow.auth")
@Validated
public class AuthProperties {
    @NotBlank
    private String adminUsername;

    @Valid
    private final Jwt jwt = new Jwt();

    @Valid
    private final Refresh refresh = new Refresh();

    @Valid
    private final Password password = new Password();

    @Valid
    private final Throttle throttle = new Throttle();

    public String getAdminUsername() {
        return UsernameNormalizer.normalize(adminUsername);
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    @AssertTrue(message = "admin-username must contain 3-32 lowercase letters, digits, or underscores")
    public boolean isAdminUsernameValid() {
        return UsernameNormalizer.isValid(adminUsername);
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Refresh getRefresh() {
        return refresh;
    }

    public Password getPassword() {
        return password;
    }

    public Throttle getThrottle() {
        return throttle;
    }

    public static class Jwt {
        @NotBlank
        private String issuer = "pixflow";

        @NotBlank
        private String secret;

        @NotNull
        private Duration accessTtl = Duration.ofMinutes(15);

        @NotNull
        private Duration clockSkew = Duration.ofSeconds(30);

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getAccessTtl() {
            return accessTtl;
        }

        public void setAccessTtl(Duration accessTtl) {
            this.accessTtl = accessTtl;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

        @AssertTrue(message = "jwt.secret must be at least 32 UTF-8 bytes")
        public boolean isSecretStrong() {
            return secret != null && !secret.isBlank() && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
        }

        @AssertTrue(message = "jwt.access-ttl must be positive")
        public boolean isAccessTtlPositive() {
            return accessTtl != null && !accessTtl.isZero() && !accessTtl.isNegative();
        }

        @AssertTrue(message = "jwt.clock-skew must not be negative")
        public boolean isClockSkewNotNegative() {
            return clockSkew != null && !clockSkew.isNegative();
        }
    }

    public static class Refresh {
        @NotNull
        private Duration ttl = Duration.ofDays(30);

        @NotBlank
        private String cookieName = "PIXFLOW_REFRESH";

        @NotBlank
        private String cookiePath = "/";

        @NotBlank
        private String cookieSameSite = "Lax";

        private boolean cookieSecure = true;

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public String getCookieName() {
            return cookieName;
        }

        public void setCookieName(String cookieName) {
            this.cookieName = cookieName;
        }

        public String getCookiePath() {
            return cookiePath;
        }

        public void setCookiePath(String cookiePath) {
            this.cookiePath = cookiePath;
        }

        public String getCookieSameSite() {
            return cookieSameSite;
        }

        public void setCookieSameSite(String cookieSameSite) {
            this.cookieSameSite = cookieSameSite;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        @AssertTrue(message = "refresh.ttl must be positive")
        public boolean isTtlPositive() {
            return ttl != null && !ttl.isZero() && !ttl.isNegative();
        }
    }

    public static class Password {
        @Min(10)
        @Max(14)
        private int bcryptStrength = 12;

        public int getBcryptStrength() {
            return bcryptStrength;
        }

        public void setBcryptStrength(int bcryptStrength) {
            this.bcryptStrength = bcryptStrength;
        }
    }

    public static class Throttle {
        @Min(1)
        private int maxFailures = 5;

        @NotNull
        private Duration window = Duration.ofMinutes(10);

        @NotNull
        private Duration blockTtl = Duration.ofMinutes(10);

        public int getMaxFailures() {
            return maxFailures;
        }

        public void setMaxFailures(int maxFailures) {
            this.maxFailures = maxFailures;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Duration getBlockTtl() {
            return blockTtl;
        }

        public void setBlockTtl(Duration blockTtl) {
            this.blockTtl = blockTtl;
        }

        @AssertTrue(message = "throttle.window must be positive")
        public boolean isWindowPositive() {
            return window != null && !window.isZero() && !window.isNegative();
        }

        @AssertTrue(message = "throttle.block-ttl must be positive")
        public boolean isBlockTtlPositive() {
            return blockTtl != null && !blockTtl.isZero() && !blockTtl.isNegative();
        }
    }
}
