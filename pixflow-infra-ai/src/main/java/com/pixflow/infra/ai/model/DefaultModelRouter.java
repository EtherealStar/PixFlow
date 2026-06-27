package com.pixflow.infra.ai.model;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.error.AiErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 基于配置的默认路由实现。
 */
public final class DefaultModelRouter implements ModelRouter {
    private final AiProperties properties;

    public DefaultModelRouter(AiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public ResolvedModel resolve(ModelRole role) {
        Objects.requireNonNull(role, "role");
        AiProperties.RoleConfig config = switch (role) {
            case PRIMARY_CHAT -> properties.roles().primaryChat();
            case VISION -> properties.roles().vision();
            case IMAGEGEN -> properties.roles().imagegen();
            case EMBEDDING -> properties.roles().embedding();
            case RERANK -> properties.roles().rerank();
        };
        if (config == null) {
            throw configurationError(role, "missing role configuration");
        }
        ModelCapability expectedCapability = expectedCapability(role);
        if (config.capability() != null && config.capability() != expectedCapability) {
            throw unsupportedCapability(role, config.capability());
        }
        String provider = config.provider() == null ? properties.defaultProvider() : config.provider();
        if (provider == null || provider.isBlank()) {
            throw configurationError(role, "missing provider");
        }
        String model = config.model();
        if (model == null || model.isBlank()) {
            throw configurationError(role, "missing model");
        }
        return new ResolvedModel(
                role,
                provider.toLowerCase(Locale.ROOT),
                model,
                expectedCapability,
                config.temperature(),
                config.maxTokens(),
                config.timeout() == null ? properties.timeout() : config.timeout());
    }

    private static ModelCapability expectedCapability(ModelRole role) {
        return switch (role) {
            case PRIMARY_CHAT -> ModelCapability.CHAT;
            case VISION -> ModelCapability.VISION;
            case IMAGEGEN -> ModelCapability.IMAGEGEN;
            case EMBEDDING -> ModelCapability.EMBEDDING;
            case RERANK -> ModelCapability.RERANK;
        };
    }

    private static PixFlowException configurationError(ModelRole role, String reason) {
        return new PixFlowException(
                AiErrorCode.MODEL_CONFIGURATION_ERROR,
                "AI configuration error: " + reason,
                null,
                Map.of("role", role.name(), "reason", reason),
                RecoveryHint.TERMINATE,
                null,
                null);
    }

    private static PixFlowException unsupportedCapability(ModelRole role, ModelCapability capability) {
        return new PixFlowException(
                AiErrorCode.MODEL_UNSUPPORTED_CAPABILITY,
                "Unsupported model capability",
                null,
                Map.of("role", role.name(), "capability", capability.name()),
                RecoveryHint.TERMINATE,
                null,
                null);
    }
}
