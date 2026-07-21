package com.pixflow.module.task.internal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.PublicationStatus;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskOutcomeQueryImplTest {
  @Test
  void exposesOnlyPublishedSuccessfulImages() {
    ProcessResultMapper mapper = mock(ProcessResultMapper.class);
    ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
    ProcessResult pending = result(6L, PublicationStatus.PENDING, null, null);
    ProcessResult published =
        result(7L, PublicationStatus.PUBLISHED, 91L, "package:7/image:91");
    when(mapper.findVisibleByTaskIdAndStatus(5L, ResultStatus.SUCCESS))
        .thenReturn(List.of(pending, published));
    when(mapper.selectById(6L)).thenReturn(pending);
    when(mapper.selectById(7L)).thenReturn(published);
    TaskOutcomeQueryImpl query = new TaskOutcomeQueryImpl(mapper, taskMapper);

    assertThat(query.successfulResults(5L))
        .singleElement()
        .satisfies(
            snapshot -> {
              assertThat(snapshot.generatedImageId()).isEqualTo(91L);
              assertThat(snapshot.referenceKey()).isEqualTo("package:7/image:91");
            });
    assertThat(query.successfulResult(6L)).isEmpty();
    assertThat(query.successfulResult(7L)).isPresent();
  }

  @Test
  void exposesImmutableCopyAndConfirmedDecisionSnapshots() {
    ProcessResultMapper resultMapper = mock(ProcessResultMapper.class);
    ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
    ProcessResult copy = result(8L, PublicationStatus.NOT_APPLICABLE, null, null);
    copy.setGeneratedCopy("A concise product description.");
    copy.setProducerProvider("openai");
    copy.setProducerModel("copy-v1");
    when(resultMapper.selectById(8L)).thenReturn(copy);
    ProcessTask task = new ProcessTask();
    task.setId(5L);
    task.setTaskType(TaskType.IMAGE_PROCESS);
    task.setConversationId("conversation-1");
    task.setPackageId(7L);
    task.setDagJson("{\"nodes\":[]}");
    task.setPayloadHash("revision-1");
    task.setSchemaVersion("1.0");
    when(taskMapper.selectById(5L)).thenReturn(task);
    TaskOutcomeQueryImpl query = new TaskOutcomeQueryImpl(resultMapper, taskMapper);

    assertThat(query.successfulCopy(8L)).get().satisfies(snapshot -> {
      assertThat(snapshot.text()).isEqualTo("A concise product description.");
      assertThat(snapshot.producerProvider()).isEqualTo("openai");
      assertThat(snapshot.producerModel()).isEqualTo("copy-v1");
    });
    assertThat(query.confirmedDecision(5L, "revision-1")).get().satisfies(snapshot -> {
      assertThat(snapshot.confirmedProposal()).isEqualTo("{\"nodes\":[]}");
      assertThat(snapshot.dagSnapshot()).isEqualTo("{\"nodes\":[]}");
      assertThat(snapshot.decisionRevision()).isEqualTo("revision-1");
    });
    assertThat(query.confirmedDecision(5L, "other-revision")).isEmpty();
  }

  private static ProcessResult result(
      long id, PublicationStatus publicationStatus, Long imageId, String referenceKey) {
    ProcessResult result = new ProcessResult();
    result.setId(id);
    result.setTaskId(5L);
    result.setStatus(ResultStatus.SUCCESS);
    result.setUnitKind(UnitKind.BRANCH);
    result.setPublicationStatus(publicationStatus);
    result.setGeneratedImageId(imageId);
    result.setPublishedReferenceKey(referenceKey);
    return result;
  }
}
