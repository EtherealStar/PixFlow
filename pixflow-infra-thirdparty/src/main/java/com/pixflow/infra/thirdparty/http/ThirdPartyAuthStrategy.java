package com.pixflow.infra.thirdparty.http;

import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;

public interface ThirdPartyAuthStrategy {
    void apply(ThirdPartyMutableRequest request, ThirdPartyProperties.Provider provider);
}
