package com.pixflow.infra.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.auth")
public class AuthProperties {
    private final Jwt jwt = new Jwt();
    private final Refresh refresh = new Refresh();
    private final Password password = new Password();
    private final Throttle throttle = new Throttle();

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
        private String issuer = "pixflow";
        private String secret = "pixflow-dev-secret-change-me-with-at-least-32-bytes";
        private Duration accessTtl = Duration.ofMinutes(15);
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
    }

    public static class Refresh {
        private Duration ttl = Duration.ofDays(30);
        private String cookieName = "PIXFLOW_REFRESH";
        private String cookiePath = "/";
        private String cookieSameSite = "Lax";
        private boolean cookieSecure;

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
    }

    public static class Password {
        private int bcryptStrength = 12;

        public int getBcryptStrength() {
            return bcryptStrength;
        }

        public void setBcryptStrength(int bcryptStrength) {
            this.bcryptStrength = bcryptStrength;
        }
    }

    public static class Throttle {
        private int maxFailures = 5;
        private Duration window = Duration.ofMinutes(10);
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
    }
}
