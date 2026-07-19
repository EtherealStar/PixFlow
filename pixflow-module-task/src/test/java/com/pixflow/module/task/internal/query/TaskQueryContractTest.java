package com.pixflow.module.task.internal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskQueryContractTest {
  private static final Instant NOW = Instant.parse("2026-07-18T16:00:00Z");

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

  @Test
  void deletingPublishedResultOnlyHidesExecutionProjection() {
    ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
    ProcessResultMapper results = mock(ProcessResultMapper.class);
    ProcessTask task = new ProcessTask();
    task.setId(101L);
    when(tasks.selectById(101L)).thenReturn(task);

    ProcessResult published = new ProcessResult();
    published.setId(201L);
    published.setTaskId(101L);
    published.setGeneratedImageId(301L);
    published.setPublishedReferenceKey("package:7/image:301");
    when(results.selectById(201L)).thenReturn(published);

    var service =
        new TaskQueryServiceImpl(
            tasks,
            results,
            mock(DownloadService.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());

    service.deleteResult(new TaskId("101"), "201");

    verify(results).softDelete(101L, 201L, NOW);
    assertThat(published.getGeneratedImageId()).isEqualTo(301L);
    assertThat(published.getPublishedReferenceKey()).isEqualTo("package:7/image:301");
  }

  @Test
  void keepsDeletedPublishedAssetAsHistoricalResultWithoutPreview() {
    ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
    ProcessResultMapper results = mock(ProcessResultMapper.class);
    DownloadService downloads = mock(DownloadService.class);
    ProcessTask task = new ProcessTask();
    task.setId(101L);
    task.setConversationId("conversation-1");
    when(tasks.selectById(101L)).thenReturn(task);

    ProcessResult published = new ProcessResult();
    published.setId(201L);
    published.setTaskId(101L);
    published.setStatus(ResultStatus.SUCCESS);
    published.setGeneratedImageId(301L);
    published.setPublishedReferenceKey("package:7/image:301");
    when(results.pageVisibleByTaskId(101L, 0, 20)).thenReturn(List.of(published));
    when(results.countVisibleByTaskId(101L)).thenReturn(1L);
    when(downloads.previewResult(published)).thenReturn(null);

    var service =
        new TaskQueryServiceImpl(
            tasks, results, downloads, Clock.systemUTC(), new ObjectMapper());

    var view = service.listResults(new TaskId("101"), new PageQuery(0, 20)).records().getFirst();

    assertThat(view.generatedImageId()).isEqualTo(301L);
    assertThat(view.referenceKey()).isEqualTo("package:7/image:301");
    assertThat(view.url()).isNull();
  }
}
