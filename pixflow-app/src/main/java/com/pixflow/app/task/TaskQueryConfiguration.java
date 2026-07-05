package com.pixflow.app.task;

import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.download.DownloadBundleBuilder;
import com.pixflow.module.task.internal.download.DownloadService;
import com.pixflow.module.task.internal.query.TaskQueryServiceImpl;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskQueryConfiguration {
    @Bean
    @ConditionalOnMissingBean(DownloadService.class)
    public DownloadService downloadService(ProcessResultMapper resultMapper,
                                           ObjectStorage objectStorage,
                                           DownloadBundleBuilder bundleBuilder,
                                           TaskProperties taskProperties,
                                           TaskMetrics taskMetrics,
                                           Clock clock) {
        return new DownloadService(resultMapper, objectStorage, bundleBuilder, taskProperties, taskMetrics, clock);
    }

    @Bean
    @ConditionalOnMissingBean(TaskQueryService.class)
    public TaskQueryService taskQueryService(ProcessTaskMapper taskMapper,
                                             ProcessResultMapper resultMapper,
                                             DownloadService downloadService,
                                             Clock clock) {
        return new TaskQueryServiceImpl(taskMapper, resultMapper, downloadService, clock);
    }
}
