package com.pixflow.module.dag.propose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.proposal.ProposalDraft;
import com.pixflow.contracts.proposal.ProposalPublicationPort;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.ir.CanonicalDag;
import com.pixflow.module.dag.ir.CanonicalDagFactory;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.validate.DagValidationResult;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.harness.tools.ProposalPublicationAuthorizer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** 校验并发布临时 DAG Proposal，不写入业务数据库。 */
public final class DagProposalService {
    private final DagValidator validator;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final CanonicalDagFactory canonicalDagFactory;

    private final ProposalPublicationPort publicationPort;

    public DagProposalService(
            DagValidator validator,
            ObjectMapper objectMapper,
            Clock clock,
            CanonicalDagFactory canonicalDagFactory,
            ProposalPublicationPort publicationPort) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.canonicalDagFactory = Objects.requireNonNull(canonicalDagFactory, "canonicalDagFactory");
        this.publicationPort = Objects.requireNonNull(publicationPort, "publicationPort");
    }

    public DagDocument parseDocument(String json) {
        return new DagJsonReader(objectMapper).read(json);
    }

    public DagProposal publish(
            String toolCallId,
            String conversationId,
            DagDocument document,
            List<String> referenceKeys,
            ProposalPublicationAuthorizer authorizer) {
        DagValidationResult validation = validator.validate(document);
        if (!validation.ok()) {
            throw new PixFlowException(DagErrorCode.DAG_INVALID_STRUCTURE,
                    "DAG 校验未通过: " + String.join("; ", validation.errors()));
        }
        CanonicalDag canonical = canonicalDagFactory.fromDocument(
                document, new DagSchemaVersion("1.0"));
        String payload = new String(canonical.canonicalJson(), StandardCharsets.UTF_8);
        ProposalDraft draft = new ProposalDraft(
                "DAG", payload, conversationId, packageId(referenceKeys), toolCallId,
                canonical.canonicalHash(), 0, referenceKeys, clock.instant());
        // canonical DAG 与引用集合都确定后才做发布授权，拒绝时不会写入 Proposal store。
        authorizer.authorize("DAG", referenceKeys, canonical.canonicalHash());
        return new DagProposal(publicationPort.publish(draft), canonical.canonicalHash());
    }

    private static String packageId(List<String> referenceKeys) {
        if (referenceKeys == null || referenceKeys.isEmpty()) {
            return "";
        }
        String first = referenceKeys.getFirst();
        int end = first.indexOf('/');
        String packagePart = end < 0 ? first : first.substring(0, end);
        return packagePart.startsWith("package:")
                ? packagePart.substring("package:".length()) : "";
    }
}
