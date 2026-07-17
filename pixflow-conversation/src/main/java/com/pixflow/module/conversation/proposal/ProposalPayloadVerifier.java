package com.pixflow.module.conversation.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 在确认边界从不可变 payload 重新计算 hash，拒绝损坏或口径不一致的 Proposal。 */
public final class ProposalPayloadVerifier {
    private final ObjectMapper objectMapper;

    private final ImagegenPayloadHasher imagegenPayloadHasher;

    public ProposalPayloadVerifier(
            ObjectMapper objectMapper, ImagegenPayloadHasher imagegenPayloadHasher) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.imagegenPayloadHasher = Objects.requireNonNull(
                imagegenPayloadHasher, "imagegenPayloadHasher");
    }

    public boolean matches(PendingProposal proposal) {
        try {
            String actual = switch (proposal.type()) {
                case DAG -> sha256(proposal.payload());
                case IMAGEGEN -> imagegenPayloadHasher.hash(
                        objectMapper.readValue(proposal.payload(), ImagegenPlan.class));
            };
            return MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    proposal.payloadHash().getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }

    private static String sha256(String payload) {
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}
