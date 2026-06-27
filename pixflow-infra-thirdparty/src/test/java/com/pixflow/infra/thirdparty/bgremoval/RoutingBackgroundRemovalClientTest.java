package com.pixflow.infra.thirdparty.bgremoval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.thirdparty.bgremoval.provider.BackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingBackgroundRemovalClientTest {

    @Test
    void failsWhenNoProviderConfigured() {
        ThirdPartyProperties properties = new ThirdPartyProperties(new ThirdPartyProperties.BgRemoval("missing"), Map.of(), null, null);
        RoutingBackgroundRemovalClient client = new RoutingBackgroundRemovalClient(properties, List.of());

        assertThatThrownBy(() -> client.remove(new BackgroundRemovalRequest(new byte[] {1}, "image/png", null, null)))
                .isInstanceOf(com.pixflow.common.error.PixFlowException.class);
    }
}
