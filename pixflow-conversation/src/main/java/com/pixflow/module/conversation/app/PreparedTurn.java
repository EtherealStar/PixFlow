package com.pixflow.module.conversation.app;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.loop.AgentTurnRequest;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.module.conversation.lock.TurnLockHandle;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PreparedTurn implements AutoCloseable {
    private final long ownerUserId;

    private final String ownerUsername;

    private final String conversationId;

    private final String prompt;

    private final List<MessageReference> references;

    private final AgentTurnRunner runner;

    private final TurnLockHandle lockHandle;

    private final AtomicBoolean closed = new AtomicBoolean();

    PreparedTurn(
            long ownerUserId,
            String ownerUsername,
            String conversationId,
            String prompt,
            List<MessageReference> references,
            AgentTurnRunner runner,
            TurnLockHandle lockHandle) {
        this.ownerUserId = ownerUserId;
        this.ownerUsername = Objects.requireNonNull(ownerUsername, "ownerUsername");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.prompt = prompt == null ? "" : prompt;
        this.references = references == null ? List.of() : List.copyOf(references);
        this.runner = Objects.requireNonNull(runner, "runner");
        this.lockHandle = Objects.requireNonNull(lockHandle, "lockHandle");
    }

    public String execute(AgentEventSink sink, CancellationToken cancellation) {
        if (closed.get()) {
            throw new IllegalStateException("prepared turn is already closed");
        }
        PermissionPrincipal principal = new PermissionPrincipal(
                Long.toString(ownerUserId), ownerUsername);
        return runner.stream(
                new AgentTurnRequest(conversationId, principal, prompt, references, cancellation), sink);
    }

    public String conversationId() {
        return conversationId;
    }

    public long ownerUserId() {
        return ownerUserId;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            lockHandle.close();
        }
    }
}
