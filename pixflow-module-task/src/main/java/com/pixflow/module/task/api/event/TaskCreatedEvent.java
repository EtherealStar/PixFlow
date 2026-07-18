package com.pixflow.module.task.api.event;

import com.pixflow.module.task.domain.model.TaskType;
import java.time.Instant;

public record TaskCreatedEvent(
    String taskId, TaskType taskType, String conversationId, long packageId, Instant occurredAt) { }
