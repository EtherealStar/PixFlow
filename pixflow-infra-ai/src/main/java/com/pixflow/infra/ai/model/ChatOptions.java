package com.pixflow.infra.ai.model;

import java.time.Duration;

/**
 * 可覆盖的模型调用参数。
 */
public record ChatOptions(Double temperature, Integer maxTokens, Duration timeout) {
    public ChatOptions {
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
    }
}
