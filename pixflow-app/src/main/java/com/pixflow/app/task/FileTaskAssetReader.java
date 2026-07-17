package com.pixflow.app.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import java.util.List;
import java.util.Locale;

/**
 * 在应用装配层把 file 的素材事实投影为 task 的中立输入，避免 task 反向依赖 file。
 */
public final class FileTaskAssetReader implements TaskAssetReader {
    private final AssetImageMapper imageMapper;

    public FileTaskAssetReader(AssetImageMapper imageMapper) {
        this.imageMapper = imageMapper;
    }

    @Override
    public List<ImageDescriptor> listImages(long packageId) {
        try {
            return imageMapper.selectList(new LambdaQueryWrapper<AssetImage>()
                            .eq(AssetImage::getPackageId, packageId)
                            .isNull(AssetImage::getDeletedAt)
                            .orderByAsc(AssetImage::getId)).stream()
                    .map(FileTaskAssetReader::descriptor)
                    .toList();
        } catch (RuntimeException failure) {
            throw assetReadFailure("读取素材包图片失败: packageId=" + packageId, failure);
        }
    }

    @Override
    public GenerativeSource sourceImage(long packageId, String sourceImageId) {
        long imageId = parseImageId(sourceImageId);
        final AssetImage image;
        try {
            image = imageMapper.selectOne(new LambdaQueryWrapper<AssetImage>()
                    .eq(AssetImage::getPackageId, packageId)
                    .eq(AssetImage::getId, imageId)
                    .isNull(AssetImage::getDeletedAt)
                    .last("limit 1"));
        } catch (RuntimeException failure) {
            throw assetReadFailure("读取生成式源图失败: packageId=" + packageId, failure);
        }
        if (image == null || image.getMinioKey() == null || image.getMinioKey().isBlank()
                || image.getSkuId() == null || image.getSkuId().isBlank()) {
            throw assetReadFailure("生成式源图不存在或元数据不完整: imageId=" + sourceImageId, null);
        }
        return new GenerativeSource(String.valueOf(image.getId()), image.getSkuId(),
                ObjectLocation.of(BucketType.PACKAGES, image.getMinioKey()));
    }

    private static ImageDescriptor descriptor(AssetImage image) {
        if (image.getId() == null || image.getMinioKey() == null || image.getMinioKey().isBlank()) {
            throw assetReadFailure("素材图片元数据不完整", null);
        }
        return new ImageDescriptor(String.valueOf(image.getId()), image.getSkuId(), image.getGroupKey(),
                image.getViewId(), image.getMinioKey(), contentType(image.getMinioKey()));
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

    private static String contentType(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        String extension = dot < 0 ? "" : objectKey.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "gif" -> "image/gif";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private static PixFlowException assetReadFailure(String message, Throwable cause) {
        return cause == null
                ? new PixFlowException(TaskErrorCode.TASK_ASSET_READ_FAILED, message)
                : new PixFlowException(TaskErrorCode.TASK_ASSET_READ_FAILED, message, cause);
    }
}
