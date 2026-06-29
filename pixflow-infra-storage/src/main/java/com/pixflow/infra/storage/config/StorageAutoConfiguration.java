package com.pixflow.infra.storage.config;

import com.pixflow.infra.storage.DefaultStorageBucketResolver;
import com.pixflow.infra.storage.MinioObjectStorage;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageBucketResolver;
import com.pixflow.infra.storage.StorageInitializer;
import com.pixflow.infra.storage.StorageProperties;
import com.pixflow.infra.storage.toolresult.ObjectStorageToolResultStorage;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * storage 自动装配。只有配置 endpoint 后才创建真实 MinIO 客户端。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@ConditionalOnProperty(prefix = "pixflow.storage", name = "endpoint")
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MinioClient minioClient(StorageProperties properties) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey());
        if (StringUtils.hasText(properties.getRegion())) {
            builder.region(properties.getRegion());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public StorageBucketResolver storageBucketResolver(StorageProperties properties) {
        return new DefaultStorageBucketResolver(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectStorage objectStorage(MinioClient minioClient, StorageBucketResolver bucketResolver, StorageProperties properties) {
        return new MinioObjectStorage(minioClient, bucketResolver, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolResultStorage toolResultStorage(ObjectStorage objectStorage) {
        return new ObjectStorageToolResultStorage(objectStorage);
    }

    @Bean
    @ConditionalOnMissingBean
    public StorageInitializer storageInitializer(MinioClient minioClient, StorageBucketResolver bucketResolver, StorageProperties properties) {
        return new StorageInitializer(minioClient, bucketResolver, properties);
    }
}
