package com.pixflow.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.file.api.AssetImageDescriptor;
import com.pixflow.module.file.api.AssetImageQuery;
import com.pixflow.module.file.api.AssetSourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileSourceImageReaderTest {
  @Test
  void exposesAReadyGeneratedImageAsMinimalImagegenSourceFacts() {
    AssetImageQuery images = mock(AssetImageQuery.class);
    var generated =
        new AssetImageDescriptor(
            31L,
            7L,
            "SKU-1",
            null,
            null,
            AssetSourceType.GENERATED,
            ObjectLocation.of(BucketType.GENERATED, "7/images/31.png"),
            "image/png");
    when(images.findAll(7L, List.of(31L))).thenReturn(List.of(generated));
    var reader = new FileSourceImageReader(images);

    var source = reader.findAll(List.of("31"), "7").getFirst();

    verify(images).findAll(7L, List.of(31L));
    assertThat(source.imageId()).isEqualTo("31");
    assertThat(source.packageId()).isEqualTo("7");
    assertThat(source.contentType()).isEqualTo("image/png");
  }
}
