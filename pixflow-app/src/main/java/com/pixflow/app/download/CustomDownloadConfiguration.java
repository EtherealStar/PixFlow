package com.pixflow.app.download;

import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.api.AssetImageQuery;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.internal.download.DownloadBundleBuilder;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomDownloadConfiguration {
    @Bean
    @ConditionalOnMissingBean(DownloadBundleBuilder.class)
    public DownloadBundleBuilder downloadBundleBuilder(ObjectStorage objectStorage, TaskProperties taskProperties) {
        return new DownloadBundleBuilder(objectStorage, taskProperties);
    }

    @Bean
    public CustomDownloadService customDownloadService(
            AssetImageQuery assetImages,
            PublishedAssetReader publishedAssets,
            ProcessResultMapper resultMapper,
            DownloadBundleBuilder bundleBuilder,
            ObjectStorage objectStorage,
            TaskProperties taskProperties,
            Clock clock) {
        return new CustomDownloadService(assetImages, publishedAssets, resultMapper,
                bundleBuilder, objectStorage, taskProperties, clock);
    }
}
