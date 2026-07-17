package com.pixflow.module.conversation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.contracts.proposal.ProposalDraft;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.PermissionSubject;
import com.pixflow.harness.permission.TaskCommandType;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.conversation.proposal.PendingProposalRepository;
import com.pixflow.module.conversation.proposal.ProposalPayloadVerifier;
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

class ConfirmationServiceTest {
    @Test
    void concurrentConfirmationsReturnTheSameTaskAndCreateOnce() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        PendingProposalRepository proposals = new PendingProposalRepository();
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
        String proposalId = proposals.publish(draft("tool-concurrent"));
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
        PendingProposalRepository proposals = new PendingProposalRepository();
        PermissionPolicy permission = mock(PermissionPolicy.class);
        TaskCommandService tasks = mock(TaskCommandService.class);
        when(permission.evaluate(any(), any())).thenReturn(PermissionDecision.allow("test"));
        when(tasks.createAndEnqueue(any())).thenReturn(new TaskId("task-1"));
        String proposalId = proposals.publish(draft("tool-1"));
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
        PendingProposalRepository proposals = new PendingProposalRepository();
        PermissionPolicy permission = mock(PermissionPolicy.class);
        TaskCommandService tasks = mock(TaskCommandService.class);
        when(permission.evaluate(any(), any())).thenReturn(PermissionDecision.allow("test"));
        String proposalId = proposals.publish(draft("tool-reject"));
        ConfirmationService service = new ConfirmationService(
                conversations, proposals, permission, tasks, verifier());

        service.reject(principal(), "conversation-1", proposalId);
        service.reject(principal(), "conversation-1", proposalId);

        assertThat(proposals.find(proposalId)).isEmpty();
    }

    @Test
    void payloadHashMismatchStopsBeforeTaskCreation() {
        ConversationService conversations = mock(ConversationService.class);
        PendingProposalRepository proposals = new PendingProposalRepository();
        PermissionPolicy permission = mock(PermissionPolicy.class);
        TaskCommandService tasks = mock(TaskCommandService.class);
        String proposalId = proposals.publish(new ProposalDraft(
                "DAG", "{}", "conversation-1", "1", "tool-tampered",
                "not-the-payload-hash", 1, List.of("package:1/image:2"),
                Instant.parse("2026-07-17T00:00:00Z")));
        ConfirmationService service = new ConfirmationService(
                conversations, proposals, permission, tasks, verifier());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.confirm(principal(), "conversation-1", proposalId))
                .isInstanceOf(com.pixflow.common.error.BusinessException.class);
        verify(tasks, times(0)).createAndEnqueue(any());
    }

    private static ProposalDraft draft(String toolCallId) {
        String payload = "{}";
        return new ProposalDraft(
                "DAG", payload, "conversation-1", "1", toolCallId,
                sha256(payload), 1, List.of("package:1/image:2"),
                Instant.parse("2026-07-17T00:00:00Z"));
    }

    private static ProposalPayloadVerifier verifier() {
        return new ProposalPayloadVerifier(
                new com.fasterxml.jackson.databind.ObjectMapper(), new ImagegenPayloadHasher());
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

}
