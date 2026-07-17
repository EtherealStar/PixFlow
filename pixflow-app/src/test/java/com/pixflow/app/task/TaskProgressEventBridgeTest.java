package com.pixflow.app.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pixflow.module.conversation.progress.ConversationProgressBridge;
import com.pixflow.module.task.api.event.ProgressEvent;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskProgressEventBridgeTest {
    @Test
    void routesTaskProgressThroughConversationTopic() {
        ProcessTaskMapper mapper = mock(ProcessTaskMapper.class);
        ConversationProgressBridge conversation = mock(ConversationProgressBridge.class);
        ProcessTask task = new ProcessTask();
        task.setConversationId("conversation-1");
        when(mapper.selectById(9L)).thenReturn(task);
        ProgressEvent event = new ProgressEvent("9", 3, 1, 0, 0, TaskStatus.RUNNING, Instant.EPOCH);

        new TaskProgressEventBridge(mapper, conversation).onProgress(event);

        verify(conversation).onProgress("conversation-1", "9", event);
    }

    @Test
    void ignoresUnknownTaskWithoutPublishing() {
        ProcessTaskMapper mapper = mock(ProcessTaskMapper.class);
        ConversationProgressBridge conversation = mock(ConversationProgressBridge.class);

        new TaskProgressEventBridge(mapper, conversation).onProgress(
                new ProgressEvent("9", 1, 1, 0, 0, TaskStatus.COMPLETED, Instant.EPOCH));

        verifyNoInteractions(conversation);
    }
}
