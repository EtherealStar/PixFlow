package com.pixflow.infra.thirdparty.bgremoval;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode;
import com.pixflow.infra.thirdparty.bgremoval.provider.BackgroundRemovalProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RoutingBackgroundRemovalClient implements BackgroundRemovalClient {
    private final ThirdPartyProperties properties;

    private final Map<String, BackgroundRemovalProvider> providers;

    public RoutingBackgroundRemovalClient(ThirdPartyProperties properties, List<BackgroundRemovalProvider> providers) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.providers = new LinkedHashMap<>();
        if (providers != null) {
            for (BackgroundRemovalProvider provider : providers) {
                if (provider != null) {
                    this.providers.put(provider.providerId().toLowerCase(Locale.ROOT), provider);
                }
            }
        }
    }

    @Override
    public BackgroundRemovalResult remove(BackgroundRemovalRequest request) {
        String providerId = properties.bgRemoval().defaultProvider();
        if (providerId == null || providerId.isBlank()) {
            throw notConfigured("missing default provider");
        }
        BackgroundRemovalProvider provider = providers.get(providerId.toLowerCase(Locale.ROOT));
        if (provider == null || !provider.capability().equalsIgnoreCase("bg-removal")) {
            throw notConfigured("provider not configured: " + providerId);
        }
        return provider.remove(request);
    }

    private static PixFlowException notConfigured(String reason) {
        return new PixFlowException(
                ThirdPartyErrorCode.THIRDPARTY_PROVIDER_NOT_CONFIGURED,
                reason,
                null,
                Map.of("reason", reason),
                RecoveryHint.TERMINATE,
                null,
                null);
    }
}
