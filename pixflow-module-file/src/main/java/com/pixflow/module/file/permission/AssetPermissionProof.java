package com.pixflow.module.file.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.permission.AssetAccessMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 由 File 当前数据库事实证明 canonical Asset Reference 可用于指定动作。 */
public final class AssetPermissionProof implements AssetAuthorizationPort {
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
            Reference reference = Reference.parse(referenceKey);
            AssetPackage assetPackage = packageService.require(reference.packageId());
            if (assetPackage.getDeletedAt() != null || !processable(assetPackage.getStatus())) {
                return ProofResult.DENIED;
            }
            return switch (reference.kind()) {
                case PACKAGE -> ProofResult.PROVED;
                case IMAGE -> proveImage(reference, mode);
                case SKU -> proveSku(reference, mode);
            };
        } catch (PixFlowException | IllegalArgumentException denied) {
            return ProofResult.DENIED;
        } catch (RuntimeException unavailable) {
            return ProofResult.UNAVAILABLE;
        }
    }

    private ProofResult proveImage(Reference reference, AssetAccessMode mode) {
        AssetImage image = imageMapper.selectById(reference.childId());
        return image != null
                && reference.packageId() == image.getPackageId()
                && image.getDeletedAt() == null
                && (mode != AssetAccessMode.PROCESS
                        || image.getMinioKey() != null && !image.getMinioKey().isBlank())
                ? ProofResult.PROVED
                : ProofResult.DENIED;
    }

    private ProofResult proveSku(Reference reference, AssetAccessMode mode) {
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, reference.packageId())
                .eq(AssetImage::getSkuId, reference.childId())
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

    private enum Kind {
        PACKAGE,
        SKU,
        IMAGE
    }

    private record Reference(long packageId, Kind kind, String childId) {
        private static Reference parse(String key) {
            String[] segments = key.split("/");
            if (segments.length < 1 || segments.length > 2
                    || !segments[0].startsWith("package:")) {
                throw new IllegalArgumentException("invalid reference");
            }
            long packageId = positiveLong(segments[0].substring("package:".length()));
            if (segments.length == 1) {
                return new Reference(packageId, Kind.PACKAGE, "");
            }
            if (segments[1].startsWith("image:")) {
                String imageId = segments[1].substring("image:".length());
                positiveLong(imageId);
                return new Reference(packageId, Kind.IMAGE, imageId);
            }
            if (segments[1].startsWith("sku:")) {
                String skuId = URLDecoder.decode(
                        segments[1].substring("sku:".length()), StandardCharsets.UTF_8);
                if (skuId.isBlank()) {
                    throw new IllegalArgumentException("invalid sku reference");
                }
                return new Reference(packageId, Kind.SKU, skuId);
            }
            throw new IllegalArgumentException("invalid reference kind");
        }

        private static long positiveLong(String value) {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("reference id must be positive");
            }
            return parsed;
        }
    }
}
