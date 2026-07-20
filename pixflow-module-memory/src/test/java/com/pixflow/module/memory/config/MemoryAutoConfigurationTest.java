package com.pixflow.module.memory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pixflow.module.file.api.AssetReferenceExpander;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.memory.recall.FileRecallReferenceResolver;
import org.junit.jupiter.api.Test;

class MemoryAutoConfigurationTest {

    @Test
    void productionReferenceResolverRequiresBothFileOwnerDependencies() {
        MemoryAutoConfiguration configuration = new MemoryAutoConfiguration();
        MemoryProperties properties = new MemoryProperties();

        Object bean = configuration.recallReferenceResolver(
                mock(AssetReferenceResolver.class),
                mock(AssetReferenceExpander.class),
                properties);

        assertThat(bean).isInstanceOf(FileRecallReferenceResolver.class);
    }
}
