package com.pixflow.module.task.infra.mq;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.infra.mq.destination.MessageDestination;
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
    MessageDestination destination =
        TaskMessageDestination.destination(properties, message.taskId());
    PublishRequest request =
        PublishRequest.of(destination.topic(), destination.tag(), message)
            .withKeys(destination.keys())
            .withSendTimeout(properties.getMq().getSendTimeout());
    PublishResult result = publisher.publish(request);
    if (result.failed()) {
      throw new PixFlowException(
          TaskErrorCode.TASK_ENQUEUE_FAILED, "task message publish failed: " + result.failure());
    }
  }
}
