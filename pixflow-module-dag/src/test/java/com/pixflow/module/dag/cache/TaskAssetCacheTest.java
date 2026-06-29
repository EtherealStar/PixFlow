package com.pixflow.module.dag.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.dag.config.DagProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TaskAssetCache 进程内 watermark 缓存测试。
 */
class TaskAssetCacheTest {

    private DagProperties properties;
    private TaskAssetCache cache;

    @BeforeEach
    void setUp() {
        properties = new DagProperties();
        cache = new TaskAssetCache(properties);
    }

    @Test
    void putAndGet_returnsStoredValue() {
        cache.put("task1", "asset1", "raster-1");
        assertThat(cache.get("task1", "asset1")).isEqualTo("raster-1");
    }

    @Test
    void get_returnsNullOnMiss() {
        assertThat(cache.get("task1", "missing")).isNull();
    }

    @Test
    void get_isolatesByTaskId() {
        cache.put("task1", "asset1", "raster-1");
        cache.put("task2", "asset1", "raster-2");
        assertThat(cache.get("task1", "asset1")).isEqualTo("raster-1");
        assertThat(cache.get("task2", "asset1")).isEqualTo("raster-2");
    }

    @Test
    void evictTask_removesAllEntriesForTask() {
        cache.put("task1", "a1", "x");
        cache.put("task1", "a2", "y");
        cache.put("task2", "a1", "z");
        assertThat(cache.size()).isEqualTo(3);
        cache.evictTask("task1");
        assertThat(cache.get("task1", "a1")).isNull();
        assertThat(cache.get("task1", "a2")).isNull();
        assertThat(cache.get("task2", "a1")).isEqualTo("z");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void clearAll_emptiesEverything() {
        cache.put("task1", "a1", "x");
        cache.put("task2", "a1", "y");
        cache.clearAll();
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void disabled_doesNotStoreOrReturn() {
        properties.getAssetCache().setEnabled(false);
        cache.put("task1", "a1", "x");
        assertThat(cache.get("task1", "a1")).isNull();
    }

    @Test
    void nullValue_isIgnored() {
        cache.put("task1", "a1", null);
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void size_reflectsStoredEntries() {
        cache.put("task1", "a1", "x");
        cache.put("task1", "a2", "y");
        cache.put("task1", "a3", "z");
        assertThat(cache.size()).isEqualTo(3);
    }
}