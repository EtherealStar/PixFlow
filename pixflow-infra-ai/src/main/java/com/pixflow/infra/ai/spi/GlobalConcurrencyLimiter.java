package com.pixflow.infra.ai.spi;

import com.pixflow.infra.ai.model.ModelRole;
import java.time.Duration;

/**
 * 全局并发限制 SPI。
 */
public interface GlobalConcurrencyLimiter {
    Permit acquire(ModelRole role, String provider, Duration waitTime);

    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
