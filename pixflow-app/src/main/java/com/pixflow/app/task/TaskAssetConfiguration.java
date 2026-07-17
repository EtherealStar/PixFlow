package com.pixflow.app.task;

import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.conversation.progress.ConversationProgressBridge;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskAssetConfiguration {
    @Bean
    @ConditionalOnMissingBean(TaskAssetReader.class)
    public TaskAssetReader taskAssetReader(AssetImageMapper imageMapper) {
        return new FileTaskAssetReader(imageMapper);
    }

    @Bean
    @ConditionalOnMissingBean(TaskProgressEventBridge.class)
    public TaskProgressEventBridge taskProgressEventBridge(ProcessTaskMapper taskMapper,
                                                           ConversationProgressBridge progressBridge) {
        return new TaskProgressEventBridge(taskMapper, progressBridge);
    }
}
