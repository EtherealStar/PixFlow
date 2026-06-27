package com.pixflow.infra.thirdparty.http;

import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import java.time.Clock;

public interface ThirdPartyRequestSigner {
    void sign(ThirdPartyMutableRequest request, ThirdPartyProperties.Provider provider, Clock clock);
}
