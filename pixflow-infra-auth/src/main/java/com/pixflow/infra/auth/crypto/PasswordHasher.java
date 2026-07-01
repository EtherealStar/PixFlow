package com.pixflow.infra.auth.crypto;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHasher {
    private final BCryptPasswordEncoder encoder;

    public PasswordHasher(int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        return rawPassword != null && passwordHash != null && encoder.matches(rawPassword, passwordHash);
    }
}
