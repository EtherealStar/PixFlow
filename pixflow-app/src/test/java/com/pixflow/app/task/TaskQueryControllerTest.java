package com.pixflow.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.common.web.PageResponse;
import com.pixflow.module.file.api.AssetReferenceCandidate;
import com.pixflow.module.file.api.AssetReferenceCatalog;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.api.query.PageQuery;
import com.pixflow.module.task.api.query.PageResult;
import com.pixflow.module.task.api.query.TaskSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskQueryControllerTest {
    private final TaskQueryService queries = mock(TaskQueryService.class);

    private final TaskCommandService commands = mock(TaskCommandService.class);

    private final AssetReferenceCatalog assetReferences = mock(AssetReferenceCatalog.class);

    private final TaskQueryController controller = new TaskQueryController(
            queries, commands, assetReferences);

    @Test
    void convertsPublicPageToTaskInternalZeroBasedPage() {
        when(queries.listByConversation(eq("conversation-1"), any(PageQuery.class)))
                .thenReturn(new PageResult<TaskSummary>(List.of(), 0, 0, 20));

        var response = controller.conversationTasks("conversation-1", 1, 20);

        ArgumentCaptor<PageQuery> page = ArgumentCaptor.forClass(PageQuery.class);
        verify(queries).listByConversation(eq("conversation-1"), page.capture());
        assertThat(page.getValue().page()).isZero();
        assertThat(response.data().page()).isEqualTo(1);
    }

    @Test
    void readsTaskOutputsFromFileLineageWithPublicPageNumber() {
        PageResponse<AssetReferenceCandidate> outputs = new PageResponse<>(List.of(), 0, 3, 10);
        when(assetReferences.listGeneratedByTaskId(42L, 3, 10, List.of("package:7/image:9")))
                .thenReturn(outputs);

        var response = controller.outputs(
                "42", 3, 10, List.of("package:7/image:9"));

        assertThat(response.data()).isSameAs(outputs);
        verify(assetReferences).listGeneratedByTaskId(
                42L, 3, 10, List.of("package:7/image:9"));
    }
}
