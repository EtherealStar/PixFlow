package com.pixflow.module.task.internal.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DownloadServiceTest {
  @Test
  void deletedPublishedAssetHasNoPreviewButKeepsHistoricalResultIdentity() {
    PublishedAssetReader assets = mock(PublishedAssetReader.class);
    ProcessResult result = new ProcessResult();
    result.setStatus(ResultStatus.SUCCESS);
    result.setGeneratedImageId(301L);
    result.setPublishedReferenceKey("package:7/image:301");
    when(assets.find("package:7/image:301")).thenReturn(Optional.empty());
    var service =
        new DownloadService(
            mock(ProcessResultMapper.class),
            mock(ObjectStorage.class),
            mock(DownloadBundleBuilder.class),
            new TaskProperties(),
            mock(TaskMetrics.class),
            Clock.systemUTC(),
            assets);

    assertThat(service.previewResult(result)).isNull();
    assertThat(result.getGeneratedImageId()).isEqualTo(301L);
    assertThat(result.getPublishedReferenceKey()).isEqualTo("package:7/image:301");
  }
}
