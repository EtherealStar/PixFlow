package com.pixflow.app.task;

import com.pixflow.module.file.runtime.AssetImageQuery;
import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.api.publication.GeneratedImagePublisher;
import com.pixflow.module.conversation.progress.ConversationProgressBridge;
import com.pixflow.module.imagegen.port.SourceImageReader;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskAssetConfiguration {
    @Bean
    public GeneratedAssetPublicationPort generatedAssetPublicationPort(
            GeneratedImagePublisher publisher) {
        return new TaskGeneratedAssetPublicationAdapter(publisher);
    }

    @Bean
    public SourceImageReader sourceImageReader(AssetContentReader contents) {
        return new FileSourceImageReader(contents);
    }

    @Bean
    @ConditionalOnMissingBean(TaskAssetReader.class)
    public TaskAssetReader taskAssetReader(AssetImageQuery images) {
        return new FileTaskAssetReader(images);
    }

    @Bean
    public PublishedAssetReader publishedAssetReader(
            AssetImageQuery images,
            ObjectStorage storage,
            CanonicalAssetReferenceCodec codec) {
        return new FilePublishedAssetReader(images, storage, codec);
    }

    @Bean
    @ConditionalOnMissingBean(TaskProgressEventBridge.class)
    public TaskProgressEventBridge taskProgressEventBridge(ProcessTaskMapper taskMapper,
                                                           ConversationProgressBridge progressBridge) {
        return new TaskProgressEventBridge(taskMapper, progressBridge);
    }
}
