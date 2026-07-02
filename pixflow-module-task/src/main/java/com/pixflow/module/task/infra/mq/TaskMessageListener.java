package com.pixflow.module.task.infra.mq;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;
import com.pixflow.module.task.internal.worker.TaskWorker;

public class TaskMessageListener implements ManagedMessageHandler<TaskMessage> {
    private final TaskWorker worker;

    public TaskMessageListener(TaskWorker worker) { this.worker = worker; }

    @Override
    public void handle(MessageEnvelope<TaskMessage> envelope) {
        worker.handle(envelope.payload());
    }
}
