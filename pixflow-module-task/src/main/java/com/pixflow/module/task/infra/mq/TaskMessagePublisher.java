package com.pixflow.module.task.infra.mq;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;

public class TaskMessagePublisher {
    private final MessagePublisher publisher;
    private final TaskProperties properties;

    public TaskMessagePublisher(MessagePublisher publisher, TaskProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    public void publish(TaskMessage message) {
        PublishRequest request = PublishRequest
                .of(properties.getMq().getExchange(), properties.getMq().getRoutingKey(), message)
                .withConfirmTimeout(properties.getMq().getConfirmTimeout());
        PublishResult result = publisher.publish(request);
        if (result.failed()) {
            throw new PixFlowException(TaskErrorCode.TASK_ENQUEUE_FAILED,
                    "task message publish failed: " + result.failure());
        }
    }
}
