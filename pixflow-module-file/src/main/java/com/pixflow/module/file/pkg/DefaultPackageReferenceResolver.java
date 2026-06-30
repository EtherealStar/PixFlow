package com.pixflow.module.file.pkg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.util.List;

public class DefaultPackageReferenceResolver implements PackageReferenceResolver {
    private final AssetPackageService packageService;
    private final AssetImageMapper imageMapper;

    public DefaultPackageReferenceResolver(AssetPackageService packageService, AssetImageMapper imageMapper) {
        this.packageService = packageService;
        this.imageMapper = imageMapper;
    }

    @Override
    public PackageReference resolve(String packageId) {
        long id = parsePackageId(packageId);
        AssetPackage assetPackage = packageService.require(id);
        List<ImageReference> images = listImages(packageId);
        return new PackageReference(
                Long.toString(id),
                assetPackage.getName(),
                id + "/",
                images);
    }

    @Override
    public List<ImageReference> listImages(String packageId) {
        long id = parsePackageId(packageId);
        return imageMapper.selectList(new LambdaQueryWrapper<AssetImage>()
                        .eq(AssetImage::getPackageId, id)
                        .orderByAsc(AssetImage::getId))
                .stream()
                .map(image -> new ImageReference(
                        String.valueOf(image.getId()),
                        image.getMinioKey(),
                        image.getOriginalPath(),
                        image.getSkuId(),
                        image.getGroupKey(),
                        image.getViewId()))
                .toList();
    }

    private static long parsePackageId(String packageId) {
        try {
            return Long.parseLong(packageId);
        } catch (RuntimeException ex) {
            throw new PixFlowException(FileErrorCode.PACKAGE_NOT_FOUND, "package not found: " + packageId, ex);
        }
    }
}
