package com.pixflow.module.rubrics.subject;

import com.pixflow.module.rubrics.model.SubjectType;
import java.util.Optional;

public interface EvaluationSubject {
    SubjectType type();

    String id();

    String snapshotHash();

    default Optional<ProductionModelIdentity> productionModel() {
        return Optional.empty();
    }

    record ProductionModelIdentity(String provider, String model) {
        public ProductionModelIdentity {
            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                throw new IllegalArgumentException("production model provider and model must not be blank");
            }
        }
    }
}
