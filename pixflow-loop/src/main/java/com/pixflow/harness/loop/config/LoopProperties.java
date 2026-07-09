package com.pixflow.harness.loop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * loop 模块配置项（{@code pixflow.loop.*}）。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@link #maxOutputRecoveryLimit}：输出截断 recovery 次数上限（escalate 不计入），默认 3，</li>
 *   <li>{@link #escalatedMaxOutputTokens}：输出截断首次 escalate 抬高到的 max output tokens，默认 64000，</li>
 *   <li>{@link #emitToolInputPreview}：{@code TOOL_CALL_READY} 是否带工具输入预览，默认 true，</li>
 *   <li>{@link #toolConcurrencyPoolSize}：并发执行工具时的线程池大小，默认 8，</li>
 *   <li>{@link #toolQueueCapacity}：工具线程池有界队列容量，默认 256，</li>
 *   <li>{@link #toolShutdownTimeoutSeconds}：应用关闭时等待工具执行结束的秒数，默认 30，</li>
 *   <li>{@link #compactionSource}：reactiveCompact metadata 中 {@code source} 字段，便于日志聚合。</li>
 * </ul>
 *
 * <p>无 maxTurns 配置项（本期决策去掉迭代上限）。
 */
@ConfigurationProperties(prefix = "pixflow.loop")
public class LoopProperties {
    public static final int MAX_TOOL_CONCURRENCY_POOL_SIZE = 64;
    public static final int MAX_TOOL_QUEUE_CAPACITY = 10_000;
    public static final int MAX_ESCALATED_OUTPUT_TOKENS = 128_000;

    private int maxOutputRecoveryLimit = 3;
    private int escalatedMaxOutputTokens = 64_000;
    private boolean emitToolInputPreview = true;
    private int toolConcurrencyPoolSize = 8;
    private int toolQueueCapacity = 256;
    private int toolShutdownTimeoutSeconds = 30;
    private String compactionSource = "loop.reactive";

    public int maxOutputRecoveryLimit() {
        return maxOutputRecoveryLimit;
    }

    public void setMaxOutputRecoveryLimit(int maxOutputRecoveryLimit) {
        if (maxOutputRecoveryLimit < 0) {
            throw new IllegalArgumentException("maxOutputRecoveryLimit must be >= 0");
        }
        this.maxOutputRecoveryLimit = maxOutputRecoveryLimit;
    }

    public int escalatedMaxOutputTokens() {
        return escalatedMaxOutputTokens;
    }

    public void setEscalatedMaxOutputTokens(int escalatedMaxOutputTokens) {
        if (escalatedMaxOutputTokens < 1 || escalatedMaxOutputTokens > MAX_ESCALATED_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("escalatedMaxOutputTokens must be between 1 and "
                    + MAX_ESCALATED_OUTPUT_TOKENS);
        }
        this.escalatedMaxOutputTokens = escalatedMaxOutputTokens;
    }

    public boolean emitToolInputPreview() {
        return emitToolInputPreview;
    }

    public void setEmitToolInputPreview(boolean emitToolInputPreview) {
        this.emitToolInputPreview = emitToolInputPreview;
    }

    public int toolConcurrencyPoolSize() {
        return toolConcurrencyPoolSize;
    }

    public void setToolConcurrencyPoolSize(int toolConcurrencyPoolSize) {
        if (toolConcurrencyPoolSize < 1 || toolConcurrencyPoolSize > MAX_TOOL_CONCURRENCY_POOL_SIZE) {
            throw new IllegalArgumentException("toolConcurrencyPoolSize must be between 1 and "
                    + MAX_TOOL_CONCURRENCY_POOL_SIZE);
        }
        this.toolConcurrencyPoolSize = toolConcurrencyPoolSize;
    }

    public int toolQueueCapacity() {
        return toolQueueCapacity;
    }

    public void setToolQueueCapacity(int toolQueueCapacity) {
        if (toolQueueCapacity < 1 || toolQueueCapacity > MAX_TOOL_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("toolQueueCapacity must be between 1 and "
                    + MAX_TOOL_QUEUE_CAPACITY);
        }
        this.toolQueueCapacity = toolQueueCapacity;
    }

    public int toolShutdownTimeoutSeconds() {
        return toolShutdownTimeoutSeconds;
    }

    public void setToolShutdownTimeoutSeconds(int toolShutdownTimeoutSeconds) {
        if (toolShutdownTimeoutSeconds < 1 || toolShutdownTimeoutSeconds > 300) {
            throw new IllegalArgumentException("toolShutdownTimeoutSeconds must be between 1 and 300");
        }
        this.toolShutdownTimeoutSeconds = toolShutdownTimeoutSeconds;
    }

    public String compactionSource() {
        return compactionSource;
    }

    public void setCompactionSource(String compactionSource) {
        this.compactionSource = compactionSource == null ? "loop.reactive" : compactionSource;
    }
}
