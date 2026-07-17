package com.pixflow.module.conversation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.harness.permission.DefaultPermissionPolicy;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.PermissionSubject;
import com.pixflow.harness.permission.TaskCommandType;
import com.pixflow.harness.permission.proof.AdministratorEligibilityPort;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ConversationAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.harness.permission.proof.ProposalAuthorizationPort;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.conversation.proposal.PendingProposalType;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.conversation.proposal.ProposalPayloadVerifier;
import com.pixflow.module.conversation.proposal.PublishProposalCommand;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConfirmationServiceTest {
    @ParameterizedTest
    @EnumSource(value = ProofFailure.class, names = {
        "ADMINISTRATOR", "CONVERSATION", "ASSET", "PROPOSAL"
    })
    void unavailableCurrentFactStopsBeforeProposalClaimAndTaskCreation(
            ProofFailure failure) {
        ConversationService conversations = mock(ConversationService.class);
        ProposalService proposals = new ProposalService();
        TaskCommandService tasks = mock(TaskCommandService.class);
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        String proposalId = proposals.publish(draft("tool-unavailable-" + failure)).proposalId();
        ConfirmationService service = new ConfirmationService(
                conversations, proposals, policyWithUnavailable(failure), tasks, verifier());

        assertThatThrownBy(() ->
                service.confirm(principal(), "conversation-1", proposalId))
                .isInstanceOf(com.pixflow.common.error.PixFlowException.class);

        assertThat(proposals.require(proposalId).rejectable()).isTrue();
        verify(tasks, never()).createAndEnqueue(any());
    }

    @Test
    void unavailableTaskFactRejectsDurableReplayWithoutCreatingAnotherTask() {
        ConversationService conversations = mock(ConversationService.class);
        ProposalService proposals = new ProposalService();
        TaskCommandService tasks = mock(TaskCommandService.class);
        when(tasks.findByIdempotencyKey(any()))
                .thenReturn(Optional.of(new TaskId("task-1")));
        ConfirmationService service = new ConfirmationService(
                conversations,
                proposals,
                policyWithUnavailable(ProofFailure.TASK),
                tasks,
                verifier());

        assertThatThrownBy(() ->
                service.confirm(principal(), "conversation-1", "proposal-1"))
                .isInstanceOf(com.pixflow.common.error.PixFlowException.class);

        verify(tasks, never()).createAndEnqueue(any());
    }

    @Test
    void concurrentConfirmationsReturnTheSameTaskAndCreateOnce() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        ProposalService proposals = new ProposalService();
        PermissionPolicy permission = mock(PermissionPolicy.class);
        TaskCommandService tasks = mock(TaskCommandService.class);
        CountDownLatch secondAuthorized = new CountDownLatch(1);
        AtomicInteger authorizationCount = new AtomicInteger();
        when(permission.evaluate(any(), any())).thenAnswer(ignored -> {
            if (authorizationCount.incrementAndGet() == 2) {
                secondAuthorized.countDown();
            }
            return PermissionDecision.allow("test");
        });
        AtomicBoolean taskCreated = new AtomicBoolean();
        when(tasks.findByIdempotencyKey(any())).thenAnswer(ignored -> taskCreated.get()
                ? Optional.of(new TaskId("task-1")) : Optional.empty());
        CountDownLatch createStarted = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(tasks.createAndEnqueue(any())).thenAnswer(ignored -> {
            createStarted.countDown();
            releaseCreate.await(2, TimeUnit.SECONDS);
            taskCreated.set(true);
            return new TaskId("task-1");
        });
        String proposalId = proposals.publish(draft("tool-concurrent")).proposalId();
        ConfirmationService service = new ConfirmationService(
                conversations, proposals, permission, tasks, verifier());

        CompletableFuture<ConfirmationSubmitResponse> first = CompletableFuture.supplyAsync(
                () -> service.confirm(principal(), "conversation-1", proposalId));
        assertThat(createStarted.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ConfirmationSubmitResponse> second = CompletableFuture.supplyAsync(
                () -> service.confirm(principal(), "conversation-1", proposalId));
        // 第二个请求必须能在 CONFIRMING 状态通过证明，并加入同一个完成信号。
        assertThat(secondAuthorized.await(1, TimeUnit.SECONDS)).isTrue();
        releaseCreate.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS).taskId()).isEqualTo("task-1");
        assertThat(second.get(2, TimeUnit.SECONDS).taskId()).isEqualTo("task-1");
        verify(tasks, times(1)).createAndEnqueue(any());
    }

    @Test
    void confirmCreatesOneTaskThenReplayReturnsTheDurableTask() {
        ConversationService conversations = mock(ConversationService.class);
        ProposalService proposals = new ProposalService();
        PermissionPolicy permission = mock(PermissionPolicy.class);
        TaskCommandService tasks = mock(TaskCommandService.class);
        when(permission.evaluate(any(), any())).thenReturn(PermissionDecision.allow("test"));
        when(tasks.createAndEnqueue(any())).thenReturn(new TaskId("task-1"));
        String proposalId = proposals.publish(draft("tool-1")).proposalId();
        when(tasks.findByIdempotencyKey("proposal:" + proposalId))
                .thenReturn(Optional.empty(), Optional.of(new TaskId("task-1")));
        ConfirmationService service = new ConfirmationService(
                conversations, proposals, permission, tasks, verifier());

        ConfirmationSubmitResponse first = service.confirm(principal(), "conversation-1", proposalId);
        ConfirmationSubmitResponse replay = service.confirm(principal(), "conversation-1", proposalId);

        assertThat(first.taskId()).isEqualTo("task-1");
        assertThat(replay.taskId()).isEqualTo("task-1");
        assertThat(proposals.find(proposalId)).isEmpty();
        verify(tasks, times(1)).createAndEnqueue(any());
        ArgumentCaptor<PermissionSubject> subject = ArgumentCaptor.forClass(PermissionSubject.class);
        verify(permission, times(2)).evaluate(any(), subject.capture());
        assertThat(subject.getAllValues().get(1))
                .isEqualTo(new PermissionSubject.TaskCommand(
                        "task-1", TaskCommandType.CONFIRM_REPLAY));
    }

    @Test
    void rejectIsIdempotentAndRemovesTheEphemeralProposal() {
        ConversationService conversations = mock(ConversationService.class);
        ProposalService proposals = new ProposalService();
        PermissionPolicy permission = mock(PermissionPolicy.class);
        TaskCommandService tasks = mock(TaskCommandService.class);
        when(permission.evaluate(any(), any())).thenReturn(PermissionDecision.allow("test"));
        String proposalId = proposals.publish(draft("tool-reject")).proposalId();
        ConfirmationService service = new ConfirmationService(
                conversations, proposals, permission, tasks, verifier());

        service.reject(principal(), "conversation-1", proposalId);
        service.reject(principal(), "conversation-1", proposalId);

        assertThat(proposals.find(proposalId)).isEmpty();
    }

    @Test
    void payloadHashMismatchStopsBeforeTaskCreation() {
        ConversationService conversations = mock(ConversationService.class);
        ProposalService proposals = new ProposalService();
        PermissionPolicy permission = mock(PermissionPolicy.class);
        TaskCommandService tasks = mock(TaskCommandService.class);
        String proposalId = proposals.publish(new PublishProposalCommand(
                PendingProposalType.DAG, "conversation-1", 1L, "tool-tampered", "{}",
                "not-the-payload-hash", 1, List.of("package:1/image:2"),
                Instant.parse("2026-07-17T00:00:00Z"))).proposalId();
        ConfirmationService service = new ConfirmationService(
                conversations, proposals, permission, tasks, verifier());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.confirm(principal(), "conversation-1", proposalId))
                .isInstanceOf(com.pixflow.common.error.BusinessException.class);
        verify(tasks, times(0)).createAndEnqueue(any());
    }

    private static PublishProposalCommand draft(String toolCallId) {
        String payload = "{}";
        return new PublishProposalCommand(
                PendingProposalType.DAG, "conversation-1", 1L, toolCallId, payload,
                sha256(payload), 1, List.of("package:1/image:2"),
                Instant.parse("2026-07-17T00:00:00Z"));
    }

    private static ProposalPayloadVerifier verifier() {
        return new ProposalPayloadVerifier(
                new com.fasterxml.jackson.databind.ObjectMapper(), new ImagegenPayloadHasher());
    }

    private static PermissionPolicy policyWithUnavailable(ProofFailure failure) {
        AdministratorEligibilityPort administrator = ignored ->
                proofResult(failure, ProofFailure.ADMINISTRATOR);
        ConversationAuthorizationPort conversation = (ignored, conversationId) ->
                proofResult(failure, ProofFailure.CONVERSATION);
        AssetAuthorizationPort asset = (ignored, referenceKey, mode) ->
                proofResult(failure, ProofFailure.ASSET);
        ProposalAuthorizationPort proposal =
                (ignored, conversationId, proposalId, payloadHash) ->
                        proofResult(failure, ProofFailure.PROPOSAL);
        TaskAuthorizationPort task = (ignored, conversationId, taskId, command) ->
                proofResult(failure, ProofFailure.TASK);
        return new DefaultPermissionPolicy(
                administrator, conversation, asset, proposal, task);
    }

    private static ProofResult proofResult(
            ProofFailure actual, ProofFailure expected) {
        return actual == expected ? ProofResult.UNAVAILABLE : ProofResult.PROVED;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(7L, "admin", "Admin");
    }

    private enum ProofFailure {
        ADMINISTRATOR,
        CONVERSATION,
        ASSET,
        PROPOSAL,
        TASK
    }

}
