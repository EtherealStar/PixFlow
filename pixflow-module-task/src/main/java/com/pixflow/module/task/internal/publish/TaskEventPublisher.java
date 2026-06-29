package com.pixflow.module.task.internal.publish;

import com.pixflow.module.task.api.event.TaskCompletedEvent;
import com.pixflow.module.task.api.event.TaskCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;

public class TaskEventPublisher {
    private final ApplicationEventPublisher publisher;

    public TaskEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publishCreated(TaskCreatedEvent event) {
        publisher.publishEvent(event);
    }

    public void publishCompleted(TaskCompletedEvent event) {
        publisher.publishEvent(event);
    }
}
