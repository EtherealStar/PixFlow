package com.pixflow.app.task;

import com.pixflow.module.file.api.publication.GeneratedImageKind;
import com.pixflow.module.file.api.publication.GeneratedImageProducer;
import com.pixflow.module.file.api.publication.GeneratedOutputContext;
import com.pixflow.module.file.api.publication.OutputTaskType;
import com.pixflow.module.file.api.publication.GeneratedImagePublisher;
import com.pixflow.module.file.api.publication.PublishGeneratedImage;
import com.pixflow.module.file.api.publication.SourceImageRef;
import com.pixflow.module.task.api.publication.GeneratedAssetCandidate;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.api.publication.PublishedGeneratedAsset;
import com.pixflow.module.conversation.app.ConversationTitleQuery;
import java.time.Instant;

/** App 组合根只负责翻译两个 owner-defined publication DTO。 */
public final class TaskGeneratedAssetPublicationAdapter
        implements GeneratedAssetPublicationPort {
    private final GeneratedImagePublisher publisher;

    private final ConversationTitleQuery conversations;

    public TaskGeneratedAssetPublicationAdapter(
            GeneratedImagePublisher publisher, ConversationTitleQuery conversations) {
        this.publisher = publisher;
        this.conversations = conversations;
    }

    @Override
    public PublishedGeneratedAsset publish(GeneratedAssetCandidate candidate) {
        GeneratedImageKind kind = GeneratedImageKind.valueOf(candidate.kind().name());
        var producer = new GeneratedImageProducer(
                kind,
                candidate.producer().provider(),
                candidate.producer().model(),
                candidate.producer().tool(),
                candidate.producer().nodeId());
        var command = new PublishGeneratedImage(
                candidate.taskId(), candidate.resultId(), candidate.unitKey(),
                candidate.resultRunEpoch(), candidate.packageId(), candidate.candidate(),
                candidate.size(), candidate.contentType(), candidate.extension(), kind,
                candidate.sourceImages().stream()
                        .map(source -> new SourceImageRef(source.imageId()))
                        .toList(),
                producer,
                new GeneratedOutputContext(
                        candidate.conversationId(),
                        conversations.titleSnapshot(candidate.conversationId()),
                        Long.toString(candidate.taskId()),
                        "IMAGE_GEN".equals(candidate.taskType())
                                ? OutputTaskType.IMAGEGEN : OutputTaskType.IMAGE_PROCESS,
                        candidate.taskCreatedAt()));
        var published = publisher.publish(command);
        return new PublishedGeneratedAsset(published.imageId(), published.referenceKey());
    }

    @Override
    public void markTaskFinished(long taskId, Instant finishedAt) {
        publisher.markTaskFinished(taskId, finishedAt);
    }
}
