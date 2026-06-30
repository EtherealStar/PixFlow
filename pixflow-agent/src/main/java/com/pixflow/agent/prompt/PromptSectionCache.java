package com.pixflow.agent.prompt;

import java.util.Optional;

/**
 * 进程内 section 渲染缓存 SPI。
 *
 * <p>对应 {@code agent.md §4.3} 的接口定义：
 * get/put/invalidate/invalidateAll/stats 五方法。
 *
 * <p>实现要点：
 * <ul>
 *   <li>进程内 ConcurrentHashMap + LRU（不引 Redis）</li>
 *   <li>大小上限可配（默认 1000）</li>
 *   <li>统计 hits/misses 暴露给 Micrometer</li>
 * </ul>
 */
public interface PromptSectionCache {

    /**
     * 查询缓存：命中即返回 render 结果。
     */
    Optional<String> get(String key, String fingerprint);

    /**
     * 写入缓存。
     */
    void put(String key, String fingerprint, String rendered);

    /**
     * 整 key 失效（如 skill 注册表变化 → 失效 available_skills）。
     */
    void invalidate(String key);

    /**
     * 全部失效（如用户偏好更新 → 失效 instruction_memory）。
     */
    void invalidateAll();

    /**
     * 统计：hits/misses。
     */
    CacheStats stats();
}
