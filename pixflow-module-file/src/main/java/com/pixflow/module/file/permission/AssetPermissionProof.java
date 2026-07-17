package com.pixflow.module.file.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.contracts.asset.SkuAssetReferenceKey;
import com.pixflow.harness.permission.AssetAccessMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;

/** 由 File 当前数据库事实证明 canonical Asset Reference 可用于指定动作。 */
public final class AssetPermissionProof implements AssetAuthorizationPort {
    private static final CanonicalAssetReferenceCodec REFERENCE_CODEC =
            new CanonicalAssetReferenceCodec();

    private final AssetPackageService packageService;

    private final AssetImageMapper imageMapper;

    public AssetPermissionProof(
            AssetPackageService packageService, AssetImageMapper imageMapper) {
        this.packageService = packageService;
        this.imageMapper = imageMapper;
    }

    @Override
    public ProofResult proveAccess(
            PermissionPrincipal principal, String referenceKey, AssetAccessMode mode) {
        if (principal == null || mode == null) {
            return ProofResult.DENIED;
        }
        try {
            AssetReferenceKey reference = REFERENCE_CODEC.parse(referenceKey);
            AssetPackage assetPackage = packageService.require(reference.packageId());
            if (assetPackage.getDeletedAt() != null || !processable(assetPackage.getStatus())) {
                return ProofResult.DENIED;
            }
            return switch (reference.kind()) {
                case PACKAGE -> ProofResult.PROVED;
                case IMAGE -> proveImage((ImageAssetReferenceKey) reference, mode);
                case SKU -> proveSku((SkuAssetReferenceKey) reference, mode);
            };
        } catch (PixFlowException | IllegalArgumentException denied) {
            return ProofResult.DENIED;
        } catch (RuntimeException unavailable) {
            return ProofResult.UNAVAILABLE;
        }
    }

    private ProofResult proveImage(
            ImageAssetReferenceKey reference, AssetAccessMode mode) {
        AssetImage image = imageMapper.selectById(reference.imageId());
        return image != null
                && reference.packageId() == image.getPackageId()
                && image.getDeletedAt() == null
                && (mode != AssetAccessMode.PROCESS
                        || image.getMinioKey() != null && !image.getMinioKey().isBlank())
                ? ProofResult.PROVED
                : ProofResult.DENIED;
    }

    private ProofResult proveSku(SkuAssetReferenceKey reference, AssetAccessMode mode) {
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, reference.packageId())
                .eq(AssetImage::getSkuId, reference.skuId())
                .isNull(AssetImage::getDeletedAt);
        if (mode == AssetAccessMode.PROCESS) {
            query.isNotNull(AssetImage::getMinioKey);
        }
        long count = imageMapper.selectCount(query);
        return count > 0 ? ProofResult.PROVED : ProofResult.DENIED;
    }

    private static boolean processable(PackageStatus status) {
        return status == PackageStatus.READY || status == PackageStatus.PARTIAL;
    }
}
