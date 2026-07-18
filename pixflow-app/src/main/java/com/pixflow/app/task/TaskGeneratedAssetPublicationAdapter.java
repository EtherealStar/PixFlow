package com.pixflow.app.task;

import com.pixflow.module.file.api.publication.GeneratedImageKind;
import com.pixflow.module.file.api.publication.GeneratedImageProducer;
import com.pixflow.module.file.api.publication.GeneratedImagePublisher;
import com.pixflow.module.file.api.publication.PublishGeneratedImage;
import com.pixflow.module.file.api.publication.SourceImageRef;
import com.pixflow.module.task.api.publication.GeneratedAssetCandidate;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.api.publication.PublishedGeneratedAsset;

/** App 组合根只负责翻译两个 owner-defined publication DTO。 */
public final class TaskGeneratedAssetPublicationAdapter
        implements GeneratedAssetPublicationPort {
    private final GeneratedImagePublisher publisher;

    public TaskGeneratedAssetPublicationAdapter(GeneratedImagePublisher publisher) {
        this.publisher = publisher;
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
                producer);
        var published = publisher.publish(command);
        return new PublishedGeneratedAsset(published.imageId(), published.referenceKey());
    }
}
