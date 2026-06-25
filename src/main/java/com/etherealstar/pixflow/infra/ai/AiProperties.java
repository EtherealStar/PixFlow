package com.etherealstar.pixflow.infra.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 调用相关配置。
 *
 * <p>绑定 {@code pixflow.ai.*} 配置项。MVP 阶段仅保留少量与具体模型解耦的开关，
 * 具体模型连接（通义千问 / DeepSeek 的 endpoint、apiKey 等）由对应 Spring AI 模型 starter
 * 通过其自身配置项提供。</p>
 */
@ConfigurationProperties(prefix = "pixflow.ai")
public class AiProperties {

    /**
     * 单次 LLM 调用的最大等待时长（毫秒），仅作记录与上层超时控制参考。默认 60 秒。
     */
    private long timeoutMillis = 60_000L;

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
