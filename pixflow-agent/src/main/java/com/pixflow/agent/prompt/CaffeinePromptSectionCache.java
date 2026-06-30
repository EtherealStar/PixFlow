package com.pixflow.agent.prompt;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pixflow.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Caffeine 实现的进程内 section 缓存。
 *
 * <p>对应 {@code agent.md §4.3} 的实现：LRU + stats，进程内 ConcurrentHashMap
 * （不引 Redis），大小上限可配。
 *
 * <p>key 复合策略：`key + "@" + fingerprint`，fingerprint 变即视作新 key。
 */
@Component
public final class CaffeinePromptSectionCache implements PromptSectionCache {

    private static final Logger log = LoggerFactory.getLogger(CaffeinePromptSectionCache.class);

    private final Cache<String, String> cache;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final boolean enabled;

    public CaffeinePromptSectionCache(AgentProperties props) {
        this.enabled = props.getPrompt().getSectionCache().isEnabled();
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.getPrompt().getSectionCache().getMaxEntries())
                .recordStats()
                .build();
        log.info("CaffeinePromptSectionCache initialized: enabled={}, maxEntries={}",
                enabled, props.getPrompt().getSectionCache().getMaxEntries());
    }

    @Override
    public Optional<String> get(String key, String fingerprint) {
        if (!enabled) {
            misses.increment();
            return Optional.empty();
        }
        String fullKey = compositeKey(key, fingerprint);
        String cached = cache.getIfPresent(fullKey);
        if (cached != null) {
            hits.increment();
            return Optional.of(cached);
        }
        misses.increment();
        return Optional.empty();
    }

    @Override
    public void put(String key, String fingerprint, String rendered) {
        if (!enabled) {
            return;
        }
        cache.put(compositeKey(key, fingerprint), rendered);
    }

    @Override
    public void invalidate(String key) {
        // Caffeine 不支持按 key 模式失效，遍历一次性移除。
        cache.asMap().keySet().removeIf(k -> k.startsWith(key + "@"));
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits.sum(), misses.sum());
    }

    private static String compositeKey(String key, String fingerprint) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        return key + "@" + fingerprint;
    }
}
