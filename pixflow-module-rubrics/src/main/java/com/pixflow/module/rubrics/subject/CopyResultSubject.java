package com.pixflow.module.rubrics.subject;

import com.pixflow.module.rubrics.model.SubjectType;
import java.time.Instant;
import java.util.Optional;

public record CopyResultSubject(
        String id,
        long taskId,
        String text,
        String producerProvider,
        String producerModel,
        Instant completedAt,
        String snapshotHash) implements EvaluationSubject {

    @Override
    public SubjectType type() {
        return SubjectType.COPY_RESULT;
    }

    @Override
    public Optional<ProductionModelIdentity> productionModel() {
        if (producerProvider == null || producerProvider.isBlank()
                || producerModel == null || producerModel.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ProductionModelIdentity(producerProvider, producerModel));
    }
}
