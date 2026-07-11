package com.pixflow.module.conversation.app;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.harness.loop.AgentTurnRequest;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.harness.loop.Attachment;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.module.conversation.lock.TurnLockHandle;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PreparedTurn implements AutoCloseable {
    private final long ownerUserId;
    private final String conversationId;
    private final String prompt;
    private final List<Attachment> attachments;
    private final AgentTurnRunner runner;
    private final TurnLockHandle lockHandle;
    private final AtomicBoolean closed = new AtomicBoolean();

    PreparedTurn(
            long ownerUserId,
            String conversationId,
            String prompt,
            List<Attachment> attachments,
            AgentTurnRunner runner,
            TurnLockHandle lockHandle) {
        this.ownerUserId = ownerUserId;
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.prompt = prompt == null ? "" : prompt;
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
        this.runner = Objects.requireNonNull(runner, "runner");
        this.lockHandle = Objects.requireNonNull(lockHandle, "lockHandle");
    }

    public String execute(AgentEventSink sink, CancellationToken cancellation) {
        if (closed.get()) {
            throw new IllegalStateException("prepared turn is already closed");
        }
        return runner.stream(new AgentTurnRequest(conversationId, prompt, attachments, cancellation), sink);
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
