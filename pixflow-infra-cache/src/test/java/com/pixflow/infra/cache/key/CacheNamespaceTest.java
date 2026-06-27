package com.pixflow.infra.cache.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CacheNamespaceTest {

    @Test
    void prefixesKeysWithEnvironmentAndNamespace() {
        CacheNamespace namespace = new DefaultCacheNamespace("dev", Duration.ofHours(1));

        CacheKey key = namespace.key("alpha", "42");

        assertThat(key.value()).isEqualTo("pixflow:dev:alpha:42");
        assertThat(key.namespace()).isEqualTo("alpha");
        assertThat(key.suggestedTtl()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void allowsDefaultTtlOverride() {
        CacheNamespace namespace = new DefaultCacheNamespace("prod", Duration.ofHours(1))
                .withDefaultTtl(Duration.ofMinutes(30));

        assertThat(namespace.key("alpha").suggestedTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void rejectsUnsafeSegments() {
        CacheNamespace namespace = new DefaultCacheNamespace("dev", Duration.ofHours(1));

        assertThatThrownBy(() -> namespace.key(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> namespace.key("a:b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> namespace.key("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
