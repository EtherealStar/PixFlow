package com.pixflow.module.task.infra.mq;

import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.destination.MessageDestination;
import com.pixflow.module.task.config.TaskProperties;

public final class TaskMessageDestination {
  private TaskMessageDestination() { }

  public static MessageDestination destination(TaskProperties properties, String taskId) {
    return MessageDestination.of(properties.getMq().getTopic(), properties.getMq().getTag())
        .withKey("task:" + taskId);
  }

  public static ConsumerBinding binding(TaskProperties properties) {
    return ConsumerBinding.of(
        properties.getMq().getTopic(),
        properties.getMq().getTag(),
        properties.getMq().getConsumerGroup(),
        TaskMessage.class);
  }
}
