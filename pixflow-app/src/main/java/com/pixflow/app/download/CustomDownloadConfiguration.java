package com.pixflow.app.download;

import com.pixflow.module.file.runtime.AssetImageQuery;
import com.pixflow.module.task.api.download.CustomDownloadBundleService;
import com.pixflow.module.task.api.download.PublishedTaskResultQuery;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomDownloadConfiguration {
    @Bean
    public CustomDownloadService customDownloadService(
            AssetImageQuery assetImages,
            PublishedAssetReader publishedAssets,
            PublishedTaskResultQuery taskResults,
            CustomDownloadBundleService bundleService) {
        return new CustomDownloadService(assetImages, publishedAssets, taskResults, bundleService);
    }
}
