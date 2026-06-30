package com.pixflow.agent.prompt;

import com.pixflow.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaffeinePromptSectionCacheTest {

    private CaffeinePromptSectionCache cache;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.getPrompt().getSectionCache().setMaxEntries(100);
        cache = new CaffeinePromptSectionCache(props);
    }

    @Test
    void get_returns_empty_when_not_present() {
        assertTrue(cache.get("key1", "fp1").isEmpty());
    }

    @Test
    void put_then_get_returns_value() {
        cache.put("key1", "fp1", "rendered text");
        assertTrue(cache.get("key1", "fp1").isPresent());
        assertEquals("rendered text", cache.get("key1", "fp1").get());
    }

    @Test
    void different_fingerprint_means_different_cache_entry() {
        cache.put("key1", "fp1", "rendered v1");
        assertTrue(cache.get("key1", "fp2").isEmpty());
        assertTrue(cache.get("key1", "fp1").isPresent());
    }

    @Test
    void invalidate_removes_key() {
        cache.put("key1", "fp1", "v1");
        cache.invalidate("key1");
        assertTrue(cache.get("key1", "fp1").isEmpty());
    }

    @Test
    void invalidateAll_removes_everything() {
        cache.put("k1", "f1", "v1");
        cache.put("k2", "f2", "v2");
        cache.invalidateAll();
        assertTrue(cache.get("k1", "f1").isEmpty());
        assertTrue(cache.get("k2", "f2").isEmpty());
    }

    @Test
    void stats_track_hits_and_misses() {
        cache.put("k1", "f1", "v1");
        cache.get("k1", "f1"); // hit
        cache.get("k2", "f2"); // miss
        CacheStats stats = cache.stats();
        assertEquals(1, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(0.5, stats.hitRate(), 0.001);
    }
}