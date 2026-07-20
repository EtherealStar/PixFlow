package com.pixflow.app.task;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.file.api.AssetContentMetadata;
import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import java.util.List;

/**
 * 在应用装配层把 file 的素材事实投影为 task 的中立输入，避免 task 反向依赖 file。
 */
public final class FileTaskAssetReader implements TaskAssetReader {
    private final AssetContentReader contents;

    public FileTaskAssetReader(AssetContentReader contents) {
        this.contents = contents;
    }

    @Override
    public List<ImageDescriptor> listImages(long packageId) {
        try {
            return contents.listReady(packageId).stream()
                    .map(FileTaskAssetReader::descriptor)
                    .toList();
        } catch (RuntimeException failure) {
            throw assetReadFailure("读取素材包图片失败: packageId=" + packageId, failure);
        }
    }

    @Override
    public GenerativeSource sourceImage(long packageId, String sourceImageId) {
        long imageId = parseImageId(sourceImageId);
        final AssetContentMetadata image;
        try {
            image = contents.require(packageId, imageId);
        } catch (RuntimeException failure) {
            throw assetReadFailure("读取生成式源图失败: packageId=" + packageId, failure);
        }
        if (image.skuId() == null || image.skuId().isBlank()) {
            throw assetReadFailure("生成式源图不存在或元数据不完整: imageId=" + sourceImageId, null);
        }
        return new GenerativeSource(String.valueOf(image.imageId()), image.skuId(),
                image.referenceKey(), image.size());
    }

    private static ImageDescriptor descriptor(AssetContentMetadata image) {
        return new ImageDescriptor(String.valueOf(image.imageId()), image.skuId(), image.groupKey(),
                image.viewId(), image.referenceKey(), image.contentType(), image.size());
    }

    private static long parseImageId(String sourceImageId) {
        try {
            long imageId = Long.parseLong(sourceImageId);
            if (imageId <= 0) {
                throw new NumberFormatException("non-positive image id");
            }
            return imageId;
        } catch (RuntimeException failure) {
            throw assetReadFailure("非法生成式源图 ID: " + sourceImageId, failure);
        }
    }

    private static PixFlowException assetReadFailure(String message, Throwable cause) {
        return cause == null
                ? new PixFlowException(TaskErrorCode.TASK_ASSET_READ_FAILED, message)
                : new PixFlowException(TaskErrorCode.TASK_ASSET_READ_FAILED, message, cause);
    }
}
