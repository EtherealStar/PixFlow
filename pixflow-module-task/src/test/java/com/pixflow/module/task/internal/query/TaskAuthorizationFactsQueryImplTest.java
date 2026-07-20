package com.pixflow.module.task.internal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.module.task.api.authorization.TaskAuthorizationFactsQuery;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import org.junit.jupiter.api.Test;

class TaskAuthorizationFactsQueryImplTest {
    @Test
    void exposesOnlyOwnerAndAllowedActionsForFailedTask() {
        ProcessTaskMapper mapper = mock(ProcessTaskMapper.class);
        ProcessTask task = new ProcessTask();
        task.setId(17L);
        task.setConversationId("conversation-3");
        task.setStatus(TaskStatus.FAILED);
        when(mapper.selectById(17L)).thenReturn(task);

        TaskAuthorizationFactsQuery query = new TaskAuthorizationFactsQueryImpl(mapper);

        assertThat(query.find("17")).hasValueSatisfying(facts -> {
            assertThat(facts.taskId()).isEqualTo("17");
            assertThat(facts.conversationId()).isEqualTo("conversation-3");
            assertThat(facts.cancellable()).isFalse();
            assertThat(facts.retryable()).isTrue();
            assertThat(facts.deletable()).isTrue();
            assertThat(facts.downloadable()).isFalse();
        });
    }

    @Test
    void invalidOrUnknownTaskHasNoAuthorizationFacts() {
        ProcessTaskMapper mapper = mock(ProcessTaskMapper.class);
        TaskAuthorizationFactsQuery query = new TaskAuthorizationFactsQueryImpl(mapper);

        assertThat(query.find("not-a-task")).isEmpty();
        assertThat(query.find("9")).isEmpty();
    }
}
