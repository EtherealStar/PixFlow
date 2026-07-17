package com.pixflow.module.conversation.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.pixflow.contracts.proposal.ProposalDraft;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.proposal.PendingProposal;
import com.pixflow.module.conversation.proposal.PendingProposalRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationPermissionProofsTest {
    @Test
    void unknownProposalTypeFailsClosed() {
        PendingProposalRepository proposals = new PendingProposalRepository();
        ProposalDraft draft = new ProposalDraft(
                "UNKNOWN", "{}", "conversation-1", "1", "tool-unknown", "hash-1", 1,
                List.of(), Instant.parse("2026-07-17T00:00:00Z"));

        assertThatThrownBy(() -> proposals.publish(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported proposal type");
    }

    @Test
    void proposalProofAllowsPendingAndConfirmingButRejectsStaleFacts() {
        PendingProposalRepository proposals = new PendingProposalRepository();
        String proposalId = proposals.publish(new ProposalDraft(
                "DAG", "{}", "conversation-1", "1", "tool-1", "hash-1", 1,
                List.of("package:1/image:2"), Instant.parse("2026-07-17T00:00:00Z")));
        ConversationPermissionProofs proofs = new ConversationPermissionProofs(
                mock(ConversationService.class), proposals);
        PermissionPrincipal principal = new PermissionPrincipal("7", "admin");

        assertThat(proofs.proveConfirmable(
                principal, "conversation-1", proposalId, "hash-1"))
                .isEqualTo(ProofResult.PROVED);
        PendingProposal pending = proposals.require(proposalId);
        proposals.claimConfirmation(pending);
        assertThat(proofs.proveConfirmable(
                principal, "conversation-1", proposalId, "hash-1"))
                .isEqualTo(ProofResult.PROVED);
        assertThat(proofs.proveConfirmable(
                principal, "other-conversation", proposalId, "hash-1"))
                .isEqualTo(ProofResult.DENIED);
        assertThat(proofs.proveConfirmable(
                principal, "conversation-1", proposalId, "stale-hash"))
                .isEqualTo(ProofResult.DENIED);
    }
}
