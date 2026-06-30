package com.pixflow.agent.prompt;

/**
 * section 缓存统计视图。
 *
 * <p>对应 {@code agent.md §4.3} 的 stats() 返回类型。
 * 暴露给 Micrometer：`pixflow.agent.prompt.section.hit` / `miss`。
 */
public record CacheStats(long hitCount, long missCount) {

    /**
     * 命中率（0-1）。miss = 0 时返回 1.0（无缺失视为 100% 命中）。
     */
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 1.0 : (double) hitCount / total;
    }
}
