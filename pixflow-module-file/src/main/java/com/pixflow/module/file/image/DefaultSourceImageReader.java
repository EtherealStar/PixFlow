package com.pixflow.module.file.image;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * File-module implementation of imagegen's source-image SPI.
 */
public class DefaultSourceImageReader implements SourceImageReader {
    private final AssetImageMapper imageMapper;

    public DefaultSourceImageReader(AssetImageMapper imageMapper) {
        this.imageMapper = imageMapper;
    }

    @Override
    public List<SourceImageInfo> findAll(List<String> imageIds, String packageId) {
        if (imageIds == null || imageIds.isEmpty()) {
            return List.of();
        }
        long parsedPackageId = parsePackageId(packageId);
        List<Long> ids = parseImageIds(imageIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return imageMapper.selectList(new LambdaQueryWrapper<AssetImage>()
                        .eq(AssetImage::getPackageId, parsedPackageId)
                        .in(AssetImage::getId, ids))
                .stream()
                .map(image -> new SourceImageInfo(
                        String.valueOf(image.getId()),
                        String.valueOf(image.getPackageId()),
                        image.getSkuId(),
                        image.getMinioKey(),
                        contentType(image),
                        image.getViewId(),
                        image.getGroupKey()))
                .toList();
    }

    private static List<Long> parseImageIds(List<String> imageIds) {
        List<Long> ids = new ArrayList<>(imageIds.size());
        for (String imageId : imageIds) {
            try {
                ids.add(Long.parseLong(imageId));
            } catch (RuntimeException ignored) {
                // Unknown/non-file image ids are treated as not found by returning no row for them.
            }
        }
        return ids;
    }

    private static long parsePackageId(String packageId) {
        try {
            return Long.parseLong(packageId);
        } catch (RuntimeException ex) {
            throw new PixFlowException(FileErrorCode.PACKAGE_NOT_FOUND, "package not found: " + packageId, ex);
        }
    }

    private static String contentType(AssetImage image) {
        String path = image.getMinioKey() == null ? image.getOriginalPath() : image.getMinioKey();
        String extension = extension(path);
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

    private static String extension(String path) {
        int index = path == null ? -1 : path.lastIndexOf('.');
        if (index < 0 || index == path.length() - 1) {
            return "";
        }
        return path.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
