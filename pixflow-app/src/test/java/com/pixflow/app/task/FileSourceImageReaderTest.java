package com.pixflow.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.module.file.api.AssetContentMetadata;
import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.api.AssetSourceType;
import org.junit.jupiter.api.Test;

class FileSourceImageReaderTest {
  @Test
  void exposesAReadyGeneratedImageAsMinimalImagegenSourceFacts() {
    AssetContentReader contents = mock(AssetContentReader.class);
    var generated =
        new AssetContentMetadata(
            "package:7/image:31",
            7L,
            31L,
            "SKU-1",
            null,
            null,
            AssetSourceType.GENERATED,
            "image/png",
            "a".repeat(64),
            10L);
    when(contents.require("package:7/image:31")).thenReturn(generated);
    var reader = new FileSourceImageReader(contents);

    var source = reader.find("package:7/image:31").orElseThrow();

    assertThat(source.referenceKey()).isEqualTo("package:7/image:31");
    assertThat(source.contentType()).isEqualTo("image/png");
  }
}
