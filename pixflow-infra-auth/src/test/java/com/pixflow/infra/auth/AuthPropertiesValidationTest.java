package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.auth.config.AuthProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthPropertiesValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsMissingJwtSecret() {
        AuthProperties properties = new AuthProperties();

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().contains("jwt.secret"));
    }

    @Test
    void rejectsShortJwtSecret() {
        AuthProperties properties = validProperties();
        properties.getJwt().setSecret("short");

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().contains("secretStrong"));
    }

    @Test
    void acceptsExplicitStrongJwtSecretAndSecureCookieDefault() {
        AuthProperties properties = validProperties();

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.getRefresh().isCookieSecure()).isTrue();
    }

    @Test
    void rejectsInvalidDurationsAndStrength() {
        AuthProperties properties = validProperties();
        properties.getJwt().setAccessTtl(Duration.ZERO);
        properties.getJwt().setClockSkew(Duration.ofSeconds(-1));
        properties.getRefresh().setTtl(Duration.ZERO);
        properties.getPassword().setBcryptStrength(4);
        properties.getThrottle().setMaxFailures(0);

        assertThat(validator.validate(properties)).hasSizeGreaterThanOrEqualTo(5);
    }

    private static AuthProperties validProperties() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("test-secret-with-more-than-32-bytes");
        return properties;
    }
}
