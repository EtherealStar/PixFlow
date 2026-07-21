package com.pixflow.module.rubrics.subject;

import com.pixflow.module.rubrics.model.SubjectType;
import java.util.Optional;

public record ImageResultSubject(String id, long taskId, String skuId, String unitKind,
                                 String imageId, String groupKey, String viewId, String branchId,
                                 long generatedImageId, String referenceKey, long bytesOut,
                                 String producerProvider, String producerModel,
                                 String snapshotHash) implements EvaluationSubject {
    @Override
    public SubjectType type() {
        return SubjectType.IMAGE_RESULT;
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
