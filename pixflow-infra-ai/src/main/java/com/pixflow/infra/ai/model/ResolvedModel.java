package com.pixflow.infra.ai.model;

import java.time.Duration;
import java.util.Objects;

/**
 * 路由后的模型解析结果。
 */
public record ResolvedModel(
        ModelRole role,
        String provider,
        String model,
        ModelCapability capability,
        Double temperature,
        Integer maxTokens,
        Duration timeout) {

    public ResolvedModel {
        role = Objects.requireNonNull(role, "role");
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        capability = Objects.requireNonNull(capability, "capability");
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
