package com.pixflow.module.task.internal.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DownloadBundleBuilderTest {
  @Test
  void streamsArchiveThroughBoundedTemporaryFile() {
    ObjectStorage storage = mock(ObjectStorage.class);
    when(storage.getStream(any())).thenReturn(new ByteArrayInputStream("image-bytes".getBytes()));
    AtomicReference<byte[]> uploaded = new AtomicReference<>();
    when(storage.put(any(), any(), anyLong(), eq("application/zip")))
        .thenAnswer(
            invocation -> {
              InputStream input = invocation.getArgument(1);
              uploaded.set(input.readAllBytes());
              ObjectLocation location = invocation.getArgument(0);
              long size = invocation.getArgument(2);
              return new ObjectRef(location.bucket(), location.key(), size, "etag");
            });
    TaskProperties properties = new TaskProperties();
    properties.getDownload().setMaxBundleBytes(1024 * 1024);

    ObjectRef result =
        new DownloadBundleBuilder(storage, properties)
            .build(
                "downloads/test.zip",
                List.of(
                    new DownloadBundleBuilder.BundleSource(
                        "photo.png", ObjectLocation.of(BucketType.RESULTS, "results/photo.png"))));

    assertThat(result.bucket()).isEqualTo(BucketType.TMP);
    assertThat(result.key()).isEqualTo("downloads/test.zip");
    assertThat(uploaded.get()).startsWith((byte) 'P', (byte) 'K');
  }

  @Test
  void rejectsArchiveAsSoonAsCompressedOutputExceedsLimit() {
    ObjectStorage storage = mock(ObjectStorage.class);
    byte[] incompressible = new byte[4096];
    new java.util.Random(1).nextBytes(incompressible);
    when(storage.getStream(any())).thenReturn(new ByteArrayInputStream(incompressible));
    TaskProperties properties = new TaskProperties();
    properties.getDownload().setMaxBundleBytes(128);

    assertThatThrownBy(
            () ->
                new DownloadBundleBuilder(storage, properties)
                    .build(
                        "downloads/test.zip",
                        List.of(
                            new DownloadBundleBuilder.BundleSource(
                                "photo.png",
                                ObjectLocation.of(BucketType.RESULTS, "results/photo.png")))))
        .isInstanceOfSatisfying(
            PixFlowException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TaskErrorCode.TASK_DOWNLOAD_BUNDLE_TOO_LARGE));
  }
}
