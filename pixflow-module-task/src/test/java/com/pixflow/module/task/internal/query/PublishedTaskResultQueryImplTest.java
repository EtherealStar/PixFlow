package com.pixflow.module.task.internal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.PublicationStatus;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import org.junit.jupiter.api.Test;

class PublishedTaskResultQueryImplTest {
  @Test
  void returnsOnlyPublishedSuccessfulResultIdentity() {
    ProcessResultMapper results = mock(ProcessResultMapper.class);
    ProcessResult pending = result(7L, PublicationStatus.PENDING, "package:2/image:7");
    ProcessResult published = result(8L, PublicationStatus.PUBLISHED, "package:2/image:8");
    when(results.selectById(7L)).thenReturn(pending);
    when(results.selectById(8L)).thenReturn(published);
    PublishedTaskResultQueryImpl query = new PublishedTaskResultQueryImpl(results);

    assertThatThrownBy(() -> query.require(7L)).isInstanceOf(PixFlowException.class);
    assertThat(query.require(8L))
        .satisfies(
            result -> {
              assertThat(result.resultId()).isEqualTo(8L);
              assertThat(result.referenceKey()).isEqualTo("package:2/image:8");
            });
  }

  private static ProcessResult result(
      long id, PublicationStatus publicationStatus, String referenceKey) {
    ProcessResult result = new ProcessResult();
    result.setId(id);
    result.setStatus(ResultStatus.SUCCESS);
    result.setPublicationStatus(publicationStatus);
    result.setPublishedReferenceKey(referenceKey);
    return result;
  }
}
