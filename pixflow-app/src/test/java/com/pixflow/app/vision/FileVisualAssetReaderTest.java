package com.pixflow.app.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.module.file.api.AssetContentMetadata;
import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.api.AssetSourceType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileVisualAssetReaderTest {
    @Test
    void projectsPublicFileContentWithoutExposingStorageLocation() throws Exception {
        AssetContentReader contents = mock(AssetContentReader.class);
        AssetContentMetadata image = new AssetContentMetadata(
                "package:7/image:11", 7L, 11L, "SKU-1", "group", "front",
                AssetSourceType.ORIGINAL, "image/png", "a".repeat(64), 3L);
        when(contents.listCurrentOriginals(7L)).thenReturn(List.of(image));
        when(contents.open(image.referenceKey())).thenReturn(new ByteArrayInputStream(
                "png".getBytes(StandardCharsets.UTF_8)));

        var reader = new FileVisionBridgeConfiguration.FileVisualAssetReader(contents);
        var result = reader.listCurrentOriginals(7L).getFirst();

        assertThat(result.imageId()).isEqualTo(11L);
        assertThat(result.contentHash()).isEqualTo("a".repeat(64));
        assertThat(result.source().openStream().readAllBytes())
                .isEqualTo("png".getBytes(StandardCharsets.UTF_8));
    }
}
