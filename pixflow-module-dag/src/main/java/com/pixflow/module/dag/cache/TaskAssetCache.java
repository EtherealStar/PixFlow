package com.pixflow.module.dag.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pixflow.module.dag.config.DagProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 任务级共享素材缓存(watermark 等)。
 *
 * <p>实现细节(对齐 dag.md §11.4):
 * <ul>
 *   <li>进程内 Caffeine 缓存,key = (taskId, assetRef)</li>
 *   <li>容量上限 = max-entries-per-task × 假设 task 数</li>
 *   <li>每次 cache hit 后调用方应做 copy(避免跨线程持有底层 RasterImage)</li>
 *   <li>task 结束事件回调 evict(taskId) 清理该任务全部条目</li>
 *   <li>不缓存 byte[]:byte[] 在 decode 后立即丢弃</li>
 * </ul>
 *
 * <p>本期用泛型 {@code Cache<String, Object>} 承载任意共享素材;watermark 在里程碑 3 集成时
 * 实际持有 {@code RasterImage}。type 边界靠调用方契约维护。
 */
@Component
public class TaskAssetCache {

    private final Cache<String, Object> cache;
    private final DagProperties properties;
    private final AtomicInteger totalSize = new AtomicInteger(0);

    public TaskAssetCache(DagProperties properties) {
        this.properties = properties;
        int maxSize = Math.max(1,
            properties.getAssetCache().getMaxEntriesPerTask() * 64); // 默认 64 并发 task 假设
        Duration ttl = Duration.ofMinutes(30);
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterAccess(ttl)
            .build();
    }

    /** 取缓存;返回 null 表示 miss。 */
    public Object get(String taskId, String assetRef) {
        if (!properties.getAssetCache().isEnabled()) {
            return null;
        }
        return cache.getIfPresent(key(taskId, assetRef));
    }

    /** 写缓存;assetRef 为 watermark 节点 params 里的 MinIO key。 */
    public void put(String taskId, String assetRef, Object value) {
        if (!properties.getAssetCache().isEnabled()) {
            return;
        }
        if (value == null) {
            return;
        }
        cache.put(key(taskId, assetRef), value);
        totalSize.set((int) cache.estimatedSize());
    }

    /** 任务结束清理(监听 task 终态事件回调,见 dag.md §11.4)。 */
    public void evictTask(String taskId) {
        // Caffeine 不支持按前缀删除;此处用 key 模式遍历
        java.util.Set<String> keysToRemove = new java.util.HashSet<>();
        cache.asMap().keySet().forEach(k -> {
            if (k.startsWith(taskId + "|")) {
                keysToRemove.add(k);
            }
        });
        keysToRemove.forEach(cache::invalidate);
        totalSize.set((int) cache.estimatedSize());
    }

    /** 清空全部(排障/单测用)。 */
    public void clearAll() {
        cache.invalidateAll();
        totalSize.set(0);
    }

    /** 当前缓存条目数(供指标暴露)。 */
    public int size() {
        return (int) cache.estimatedSize();
    }

    private static String key(String taskId, String assetRef) {
        return taskId + "|" + assetRef;
    }
}