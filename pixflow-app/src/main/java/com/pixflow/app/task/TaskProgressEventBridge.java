package com.pixflow.app.task;

import com.pixflow.module.conversation.progress.ConversationProgressBridge;
import com.pixflow.module.task.api.event.ProgressEvent;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import org.springframework.context.event.EventListener;

/** 把 task 的逻辑进度事件补充会话路由信息后交给 app 级 STOMP 传输。 */
public final class TaskProgressEventBridge {
    private final ProcessTaskMapper taskMapper;
    private final ConversationProgressBridge progressBridge;

    public TaskProgressEventBridge(ProcessTaskMapper taskMapper, ConversationProgressBridge progressBridge) {
        this.taskMapper = taskMapper;
        this.progressBridge = progressBridge;
    }

    @EventListener
    public void onProgress(ProgressEvent event) {
        final long taskId;
        try {
            taskId = Long.parseLong(event.taskId());
        } catch (NumberFormatException ignored) {
            return;
        }
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null || task.getConversationId() == null || task.getConversationId().isBlank()) {
            return;
        }
        progressBridge.onProgress(task.getConversationId(), event.taskId(), event);
    }
}
