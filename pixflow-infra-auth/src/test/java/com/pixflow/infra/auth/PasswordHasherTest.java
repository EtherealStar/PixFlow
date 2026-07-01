package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.auth.crypto.PasswordHasher;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    @Test
    void hashesAndVerifiesPassword() {
        PasswordHasher hasher = new PasswordHasher(10);

        String hash = hasher.hash("correct-password");

        assertThat(hash).isNotEqualTo("correct-password");
        assertThat(hasher.matches("correct-password", hash)).isTrue();
        assertThat(hasher.matches("wrong-password", hash)).isFalse();
    }
}
