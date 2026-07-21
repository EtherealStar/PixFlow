package com.pixflow.app.task;

import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.api.publication.GeneratedImagePublisher;
import com.pixflow.module.imagegen.port.SourceImageReader;
import com.pixflow.module.imagegen.port.SourceImageContent;
import com.pixflow.module.dag.exec.PipelineUnitExecutor;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import com.pixflow.module.conversation.app.ConversationTitleQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskAssetConfiguration {
    @Bean
    public GeneratedAssetPublicationPort generatedAssetPublicationPort(
            GeneratedImagePublisher publisher, ConversationTitleQuery conversations) {
        return new TaskGeneratedAssetPublicationAdapter(publisher, conversations);
    }

    @Bean
    public SourceImageReader sourceImageReader(AssetContentReader contents) {
        return new FileSourceImageReader(contents);
    }

    @Bean
    public PipelineUnitExecutor.SourceReader dagSourceReader(AssetContentReader contents) {
        return new PipelineUnitExecutor.SourceReader() {
            @Override
            public java.io.InputStream openStream(String referenceKey) {
                return contents.open(referenceKey);
            }

            @Override
            public long statSize(String referenceKey) {
                return contents.require(referenceKey).size();
            }
        };
    }

    @Bean
    public SourceImageContent sourceImageContent(AssetContentReader contents) {
        return new SourceImageContent() {
            @Override
            public Metadata require(String referenceKey) {
                var metadata = contents.require(referenceKey);
                return new Metadata(metadata.contentType(), metadata.size());
            }

            @Override
            public java.io.InputStream open(String referenceKey) {
                return contents.open(referenceKey);
            }
        };
    }

    @Bean
    public TaskAssetReader taskAssetReader(AssetContentReader contents) {
        return new FileTaskAssetReader(contents);
    }

    @Bean
    public PublishedAssetReader publishedAssetReader(
            AssetContentReader contents) {
        return new FilePublishedAssetReader(contents);
    }

}
