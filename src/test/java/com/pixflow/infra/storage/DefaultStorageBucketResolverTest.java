package com.pixflow.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DefaultStorageBucketResolverTest {

    @Test
    void resolvesDefaultBucketNames() {
        StorageProperties properties = new StorageProperties();
        DefaultStorageBucketResolver resolver = new DefaultStorageBucketResolver(properties);

        assertThat(resolver.resolve(BucketType.PACKAGES)).isEqualTo("pixflow-packages");
        assertThat(resolver.resolve(BucketType.RESULTS)).isEqualTo("pixflow-results");
        assertThat(resolver.resolve(BucketType.GENERATED)).isEqualTo("pixflow-generated");
        assertThat(resolver.resolve(BucketType.TOOL_RESULTS)).isEqualTo("pixflow-tool-results");
        assertThat(resolver.resolve(BucketType.TMP)).isEqualTo("pixflow-tmp");
    }

    @Test
    void resolvesConfiguredBucketNames() {
        StorageProperties properties = new StorageProperties();
        properties.getBuckets().setPackages("custom-packages");
        DefaultStorageBucketResolver resolver = new DefaultStorageBucketResolver(properties);

        assertThat(resolver.resolve(BucketType.PACKAGES)).isEqualTo("custom-packages");
    }

    @Test
    void rejectsMissingBucketName() {
        StorageProperties properties = new StorageProperties();
        properties.getBuckets().setTmp(" ");
        DefaultStorageBucketResolver resolver = new DefaultStorageBucketResolver(properties);

        assertThatThrownBy(() -> resolver.resolve(BucketType.TMP))
                .isInstanceOf(StorageException.class);
    }
}
