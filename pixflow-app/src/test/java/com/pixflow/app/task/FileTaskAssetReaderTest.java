package com.pixflow.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileTaskAssetReaderTest {
    @Test
    void mapsVisibleImagesAndResolvesGenerativeSourceWithinPackage() {
        AssetImageMapper mapper = mock(AssetImageMapper.class);
        AssetImage first = image(11L, 7L, "SKU-1", "group-a", "front", "7/a/front.png");
        AssetImage second = image(12L, 7L, "SKU-2", null, null, "7/b/photo.jpg");
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        when(mapper.selectOne(any())).thenReturn(second);

        FileTaskAssetReader reader = new FileTaskAssetReader(mapper);

        assertThat(reader.listImages(7L)).extracting("imageId").containsExactly("11", "12");
        assertThat(reader.listImages(7L).getFirst().contentType()).isEqualTo("image/png");
        var source = reader.sourceImage(7L, "12");
        assertThat(source.skuId()).isEqualTo("SKU-2");
        assertThat(source.location().bucket()).isEqualTo(BucketType.PACKAGES);
        assertThat(source.location().key()).isEqualTo("7/b/photo.jpg");
    }

    @Test
    void rejectsInvalidOrMissingGenerativeSource() {
        AssetImageMapper mapper = mock(AssetImageMapper.class);
        FileTaskAssetReader reader = new FileTaskAssetReader(mapper);

        assertThatThrownBy(() -> reader.sourceImage(7L, "not-a-number"))
                .isInstanceOfSatisfying(PixFlowException.class,
                        failure -> assertThat(failure.code()).isEqualTo(TaskErrorCode.TASK_ASSET_READ_FAILED));
        assertThatThrownBy(() -> reader.sourceImage(7L, "99"))
                .isInstanceOfSatisfying(PixFlowException.class,
                        failure -> assertThat(failure.code()).isEqualTo(TaskErrorCode.TASK_ASSET_READ_FAILED));
    }

    private static AssetImage image(long id, long packageId, String skuId, String groupKey,
                                    String viewId, String minioKey) {
        AssetImage image = new AssetImage();
        image.setId(id);
        image.setPackageId(packageId);
        image.setSkuId(skuId);
        image.setGroupKey(groupKey);
        image.setViewId(viewId);
        image.setMinioKey(minioKey);
        return image;
    }
}
