package com.pixflow.app.conversation;

import com.pixflow.common.error.BusinessException;
import com.pixflow.module.conversation.app.ConversationDeletionGuard;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.api.query.PageQuery;
import com.pixflow.module.task.api.query.PageResult;
import com.pixflow.module.task.api.query.TaskSummary;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConversationLifecycleConfiguration {
    private static final int PAGE_SIZE = 200;

    @Bean
    public ConversationDeletionGuard conversationDeletionGuard(TaskQueryService tasks) {
        return (administratorId, conversationId) -> {
            int page = 0;
            while (true) {
                PageResult<TaskSummary> result = tasks.listByConversation(
                        conversationId, new PageQuery(page, PAGE_SIZE));
                boolean active = result.records().stream().anyMatch(task -> !task.status().terminal());
                if (active) {
                    throw new BusinessException(
                            ConversationErrorCode.CONVERSATION_BUSY,
                            "conversation has a running task",
                            Map.of("conversationId", conversationId));
                }
                if ((long) (page + 1) * PAGE_SIZE >= result.total()) {
                    return;
                }
                page++;
            }
        };
    }
}
