package com.pixflow.module.conversation.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.proposal.PendingProposalType;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.conversation.proposal.PublishProposalCommand;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConversationPermissionProofsTest {
    @Test
    void conversationFactDependencyFailureIsUnavailable() {
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.requireActive(7L, "conversation-1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        ConversationPermissionProofs proofs = new ConversationPermissionProofs(
                conversations, new ProposalService());

        assertThat(proofs.proveAccess(
                new PermissionPrincipal("7", "admin"), "conversation-1"))
                .isEqualTo(ProofResult.UNAVAILABLE);
    }

    @Test
    void proposalFactDependencyFailureIsUnavailable() {
        ProposalService proposals = mock(ProposalService.class);
        when(proposals.find("proposal-1"))
                .thenThrow(new IllegalStateException("proposal store unavailable"));
        ConversationPermissionProofs proofs = new ConversationPermissionProofs(
                mock(ConversationService.class), proposals);

        assertThat(proofs.proveConfirmable(
                new PermissionPrincipal("7", "admin"),
                "conversation-1",
                "proposal-1",
                "hash-1"))
                .isEqualTo(ProofResult.UNAVAILABLE);
    }

    @Test
    void missingProposalTypeFailsClosed() {
        ProposalService proposals = new ProposalService();

        assertThatThrownBy(() -> proposals.publish(new PublishProposalCommand(
                null, "conversation-1", 1L, "tool-unknown", "{}", "hash-1", 1,
                List.of(), Instant.parse("2026-07-17T00:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void proposalProofAllowsPendingAndConfirmingButRejectsStaleFacts() throws Exception {
        ProposalService proposals = new ProposalService();
        String proposalId = proposals.publish(new PublishProposalCommand(
                PendingProposalType.DAG, "conversation-1", 1L, "tool-1", "{}", "hash-1", 1,
                List.of("package:1/image:2"), Instant.parse("2026-07-17T00:00:00Z")))
                .proposalId();
        ConversationPermissionProofs proofs = new ConversationPermissionProofs(
                mock(ConversationService.class), proposals);
        PermissionPrincipal principal = new PermissionPrincipal("7", "admin");

        assertThat(proofs.proveConfirmable(
                principal, "conversation-1", proposalId, "hash-1"))
                .isEqualTo(ProofResult.PROVED);
        CountDownLatch confirming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> confirmation = CompletableFuture.supplyAsync(() ->
                proposals.confirm(proposalId, ignored -> {
                    confirming.countDown();
                    await(release);
                    return "task-1";
                }));
        try {
            assertThat(confirming.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(proofs.proveConfirmable(
                    principal, "conversation-1", proposalId, "hash-1"))
                    .isEqualTo(ProofResult.PROVED);
            assertThat(proofs.proveConfirmable(
                    principal, "other-conversation", proposalId, "hash-1"))
                    .isEqualTo(ProofResult.DENIED);
            assertThat(proofs.proveConfirmable(
                    principal, "conversation-1", proposalId, "stale-hash"))
                    .isEqualTo(ProofResult.DENIED);
        } finally {
            release.countDown();
        }
        assertThat(confirmation.join()).isEqualTo("task-1");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("confirmation test timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("confirmation test interrupted", interrupted);
        }
    }
}
