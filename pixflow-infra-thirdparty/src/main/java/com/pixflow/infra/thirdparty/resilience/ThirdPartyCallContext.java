package com.pixflow.infra.thirdparty.resilience;

import java.time.Duration;

public record ThirdPartyCallContext(String api, String providerId, Duration semaphoreWaitTime) {
}
