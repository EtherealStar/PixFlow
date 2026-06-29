package com.pixflow.module.task.infra.mq;

import com.pixflow.module.task.internal.worker.TaskWorker;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

public class TaskMessageListener {
    private final TaskWorker worker;

    public TaskMessageListener(TaskWorker worker) {
        this.worker = worker;
    }

    @RabbitListener(queues = "${pixflow.task.mq.queue:pixflow.task.execute}")
    public void onMessage(TaskMessage message) {
        worker.handle(message);
    }
}
