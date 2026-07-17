package com.pixflow.module.dag.propose;

import com.pixflow.contracts.asset.AssetReferenceKey;
import java.util.List;
import java.util.Objects;

/** DAG owner 深校验后交给 App 编排层的不可变结果。 */
public record ValidatedImagePlan(
        String canonicalPayload,
        String payloadHash,
        long packageId,
        List<AssetReferenceKey> references) {

    public ValidatedImagePlan {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        Objects.requireNonNull(payloadHash, "payloadHash");
        if (packageId <= 0) {
            throw new IllegalArgumentException("packageId must be positive");
        }
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        if (references.isEmpty()) {
            throw new IllegalArgumentException("references must not be empty");
        }
    }
}
