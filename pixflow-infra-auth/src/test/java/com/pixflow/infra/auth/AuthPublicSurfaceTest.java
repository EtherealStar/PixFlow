package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.service.AuthService;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuthPublicSurfaceTest {
    @Test
    void authServiceExposesAuthenticationButNoRegistrationCapability() {
        assertThat(Arrays.stream(AuthService.class.getDeclaredMethods())
                        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                        .map(Method::getName))
                .containsExactlyInAnyOrder("login", "refresh", "logout", "authenticateAccessToken");
    }

    @Test
    void principalContainsNoRoleOrAccountStatus() {
        assertThat(Arrays.stream(AuthPrincipal.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("userId", "username", "displayName");
    }
}
