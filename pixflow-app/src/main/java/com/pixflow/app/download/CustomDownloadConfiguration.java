package com.pixflow.app.download;

import com.pixflow.module.task.api.download.CustomDownloadBundleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomDownloadConfiguration {
    @Bean
    public CustomDownloadService customDownloadService(CustomDownloadBundleService bundleService) {
        return new CustomDownloadService(bundleService);
    }
}
