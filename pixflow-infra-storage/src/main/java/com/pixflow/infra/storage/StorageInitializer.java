package com.pixflow.infra.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.ResponseDate;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动期桶初始化和 TMP 生命周期声明。
 */
public class StorageInitializer implements ApplicationRunner {
    private static final String TMP_LIFECYCLE_RULE_ID = "pixflow-tmp-expire";

    private final MinioClient minioClient;
    private final StorageBucketResolver bucketResolver;
    private final StorageProperties properties;

    public StorageInitializer(MinioClient minioClient, StorageBucketResolver bucketResolver, StorageProperties properties) {
        this.minioClient = minioClient;
        this.bucketResolver = bucketResolver;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (BucketType bucket : BucketType.values()) {
            ensureBucket(bucket);
        }
        configureTmpLifecycle();
    }

    private void ensureBucket(BucketType bucket) {
        String bucketName = bucketResolver.resolve(bucket);
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists && properties.isAutoCreateBucket()) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                return;
            }
            if (!exists) {
                throw new StorageException("CHECK_BUCKET", bucket, null, false, "bucket does not exist: " + bucketName, null);
            }
        } catch (StorageException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StorageException("MAKE_BUCKET", bucket, null, true, "failed to initialize bucket: " + bucketName, ex);
        }
    }

    private void configureTmpLifecycle() {
        if (properties.getTmpExpiryDays() <= 0) {
            return;
        }
        String bucketName = bucketResolver.resolve(BucketType.TMP);
        try {
            // TMP 桶只存临时中间产物，生命周期规则作为异常残留的兜底清理。
            LifecycleRule rule = new LifecycleRule(
                    Status.ENABLED,
                    null,
                    new Expiration((ResponseDate) null, properties.getTmpExpiryDays(), null),
                    new RuleFilter(""),
                    TMP_LIFECYCLE_RULE_ID,
                    null,
                    null,
                    null);
            LifecycleConfiguration configuration = new LifecycleConfiguration(List.of(rule));
            minioClient.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                    .bucket(bucketName)
                    .config(configuration)
                    .build());
        } catch (Exception ex) {
            throw new StorageException("SET_LIFECYCLE", BucketType.TMP, null, true, "failed to set tmp bucket lifecycle", ex);
        }
    }
}
