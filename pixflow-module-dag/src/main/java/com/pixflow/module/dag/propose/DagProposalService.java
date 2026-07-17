package com.pixflow.module.dag.propose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.ir.CanonicalDag;
import com.pixflow.module.dag.ir.CanonicalDagFactory;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.validate.DagValidationResult;
import com.pixflow.module.dag.validate.DagValidator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** 解析并深校验 Image Plan，不负责 Proposal id 或发布。 */
public final class DagProposalService {
    private final DagValidator validator;

    private final ObjectMapper objectMapper;

    private final CanonicalDagFactory canonicalDagFactory;

    public DagProposalService(
            DagValidator validator,
            ObjectMapper objectMapper,
            CanonicalDagFactory canonicalDagFactory) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.canonicalDagFactory = Objects.requireNonNull(canonicalDagFactory, "canonicalDagFactory");
    }

    public DagDocument parseDocument(String json) {
        return new DagJsonReader(objectMapper).read(json);
    }

    public ValidatedImagePlan validate(
            DagDocument document,
            List<AssetReferenceKey> references) {
        List<AssetReferenceKey> immutableReferences = List.copyOf(references);
        if (immutableReferences.isEmpty()) {
            throw new PixFlowException(
                    DagErrorCode.DAG_INVALID_STRUCTURE, "referenceKeys must not be empty");
        }
        long packageId = immutableReferences.getFirst().packageId();
        if (immutableReferences.stream().anyMatch(reference -> reference.packageId() != packageId)) {
            throw new PixFlowException(
                    DagErrorCode.DAG_INVALID_STRUCTURE, "all references must belong to one package");
        }
        DagValidationResult validation = validator.validate(document);
        if (!validation.ok()) {
            throw new PixFlowException(DagErrorCode.DAG_INVALID_STRUCTURE,
                    "DAG 校验未通过: " + String.join("; ", validation.errors()));
        }
        CanonicalDag canonical = canonicalDagFactory.fromDocument(
                document, new DagSchemaVersion("1.0"));
        String payload = new String(canonical.canonicalJson(), StandardCharsets.UTF_8);
        return new ValidatedImagePlan(
                payload, canonical.canonicalHash(), packageId, immutableReferences);
    }
}
