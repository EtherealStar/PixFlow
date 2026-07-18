package com.pixflow.module.task.internal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.PublicationStatus;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskOutcomeQueryImplTest {
  @Test
  void exposesOnlyPublishedSuccessfulImages() {
    ProcessResultMapper mapper = mock(ProcessResultMapper.class);
    ProcessResult pending = result(6L, PublicationStatus.PENDING, null, null);
    ProcessResult published =
        result(7L, PublicationStatus.PUBLISHED, 91L, "package:7/image:91");
    when(mapper.findVisibleByTaskIdAndStatus(5L, ResultStatus.SUCCESS))
        .thenReturn(List.of(pending, published));
    when(mapper.selectById(6L)).thenReturn(pending);
    when(mapper.selectById(7L)).thenReturn(published);
    TaskOutcomeQueryImpl query = new TaskOutcomeQueryImpl(mapper);

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
