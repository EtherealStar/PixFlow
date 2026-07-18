package com.pixflow.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.file.api.AssetImageDescriptor;
import com.pixflow.module.file.api.AssetImageQuery;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileTaskAssetReaderTest {
    @Test
    void mapsVisibleImagesAndResolvesGenerativeSourceWithinPackage() {
        AssetImageQuery images = mock(AssetImageQuery.class);
        var first = image(11L, 7L, "SKU-1", "group-a", "front", "7/a/front.png");
        var second = image(12L, 7L, "SKU-2", null, null, "7/b/photo.jpg");
        when(images.listReady(7L)).thenReturn(List.of(first, second));
        when(images.require(7L, 12L)).thenReturn(second);

        FileTaskAssetReader reader = new FileTaskAssetReader(images);

        assertThat(reader.listImages(7L)).extracting("imageId").containsExactly("11", "12");
        assertThat(reader.listImages(7L).getFirst().contentType()).isEqualTo("image/png");
        var source = reader.sourceImage(7L, "12");
        assertThat(source.skuId()).isEqualTo("SKU-2");
        assertThat(source.location().bucket()).isEqualTo(BucketType.PACKAGES);
        assertThat(source.location().key()).isEqualTo("7/b/photo.jpg");
    }

    @Test
    void rejectsInvalidOrMissingGenerativeSource() {
        AssetImageQuery images = mock(AssetImageQuery.class);
        when(images.require(anyLong(), anyLong()))
                .thenThrow(new IllegalArgumentException("image not found"));
        FileTaskAssetReader reader = new FileTaskAssetReader(images);

        assertThatThrownBy(() -> reader.sourceImage(7L, "not-a-number"))
                .isInstanceOfSatisfying(PixFlowException.class,
                        failure -> assertThat(failure.code()).isEqualTo(TaskErrorCode.TASK_ASSET_READ_FAILED));
        assertThatThrownBy(() -> reader.sourceImage(7L, "99"))
                .isInstanceOfSatisfying(PixFlowException.class,
                        failure -> assertThat(failure.code()).isEqualTo(TaskErrorCode.TASK_ASSET_READ_FAILED));
    }

    private static AssetImageDescriptor image(long id, long packageId, String skuId,
            String groupKey, String viewId, String minioKey) {
        String contentType = minioKey.endsWith(".png") ? "image/png" : "image/jpeg";
        return new AssetImageDescriptor(id, packageId, skuId, viewId, groupKey,
                AssetSourceType.ORIGINAL, new ObjectLocation(BucketType.PACKAGES, minioKey),
                contentType);
    }
}
