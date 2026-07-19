package com.pixflow.module.file.internal.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class DefaultAssetContentReaderTest {
    @Test
    void returnsSafeMetadataAndOpensBytesWithoutExposingObjectLocation() throws Exception {
        AssetImageMapper mapper = mock(AssetImageMapper.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AssetImage image = new AssetImage();
        image.setId(9L);
        image.setPackageId(3L);
        image.setSkuId("SKU-1");
        image.setSourceType("GENERATED");
        image.setPublicationStatus("READY");
        image.setStableBucket(BucketType.GENERATED.name());
        image.setMinioKey("3/generated/9.png");
        image.setContentType("image/png");
        image.setByteSize(4L);
        when(mapper.selectById(9L)).thenReturn(image);
        ObjectLocation location = ObjectLocation.of(BucketType.GENERATED, "3/generated/9.png");
        when(storage.getStream(location)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        DefaultAssetContentReader reader = new DefaultAssetContentReader(
                new CanonicalAssetReferenceCodec(), mapper, storage);

        var metadata = reader.require("package:3/image:9");

        assertThat(metadata.sourceType()).isEqualTo(AssetSourceType.GENERATED);
        assertThat(metadata.size()).isEqualTo(4L);
        assertThat(reader.open("package:3/image:9").readAllBytes()).containsExactly(1, 2, 3, 4);
    }
}
