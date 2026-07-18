package com.pixflow.module.task.internal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.query.PageQuery;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.download.DownloadService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskQueryContractTest {
  @Test
  void exposesDerivedSourceAndStructuredFinalFailure() {
    ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
    ProcessResultMapper results = mock(ProcessResultMapper.class);
    ProcessTask task = new ProcessTask();
    task.setId(101L);
    task.setTaskType(TaskType.IMAGE_PROCESS);
    task.setStatus(TaskStatus.FAILED);
    task.setTotalCount(1);
    task.setRetryOfTaskId(10L);
    when(tasks.selectById(101L)).thenReturn(task);
    when(results.countByStatus(101L, ResultStatus.SUCCESS)).thenReturn(0);
    when(results.countByStatus(101L, ResultStatus.FAILED)).thenReturn(1);
    when(results.countByStatus(101L, ResultStatus.SKIPPED)).thenReturn(0);

    ProcessResult failed = new ProcessResult();
    failed.setId(201L);
    failed.setTaskId(101L);
    failed.setStatus(ResultStatus.FAILED);
    failed.setUnitKind(UnitKind.BRANCH);
    failed.setFailureCode("THIRDPARTY_RATE_LIMITED");
    failed.setFailureCategory("DEPENDENCY");
    failed.setFailureRecovery("RETRY");
    failed.setFailedNodeId("remove-bg-1");
    failed.setFailedTool("remove_bg");
    failed.setAttemptCount(3);
    failed.setErrorMsg("服务暂时繁忙");
    failed.setFailureDetailsJson("{\"provider\":\"aliyun\",\"apiKey\":\"secret-value\"}");
    when(results.pageVisibleByTaskId(101L, 0, 20)).thenReturn(List.of(failed));
    when(results.countVisibleByTaskId(101L)).thenReturn(1L);

    var service =
        new TaskQueryServiceImpl(
            tasks, results, mock(DownloadService.class), Clock.systemUTC(), new ObjectMapper());

    assertThat(service.getStatus(new TaskId("101")).retryOfTaskId()).isEqualTo("10");
    var view = service.listResults(new TaskId("101"), new PageQuery(0, 20)).records().getFirst();
    assertThat(view.failure().code()).isEqualTo("THIRDPARTY_RATE_LIMITED");
    assertThat(view.failure().attemptCount()).isEqualTo(3);
    assertThat(view.failure().safeMessage()).isEqualTo("服务暂时繁忙");
    assertThat(view.failure().details()).containsEntry("provider", "aliyun");
    assertThat(view.failure().details()).containsEntry("apiKey", "***");
  }
}
