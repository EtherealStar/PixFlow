package com.pixflow.module.file.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import com.pixflow.module.file.internal.reference.DefaultAssetReferenceService;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultAssetReferenceServiceTest {
    @Test
    void expandsPackageToCanonicalImageReferencesWithoutStorageDetails() {
        AssetPackageService packages = mock(AssetPackageService.class);
        AssetImageMapper images = mock(AssetImageMapper.class);
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(7L);
        assetPackage.setName("summer.zip");
        assetPackage.setStatus(PackageStatus.READY);
        when(packages.require(7L)).thenReturn(assetPackage);
        AssetImage image = image(11L, 7L, "SKU-1", "front.png", "7/images/front.png");
        when(images.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(image));

        DefaultAssetReferenceService service = new DefaultAssetReferenceService(
                new CanonicalAssetReferenceCodec(), packages, images);

        ExpandedAssetSet result = service.expand(List.of("package:7"), AssetUse.PROCESS);

        assertThat(result.images()).singleElement().satisfies(resolved -> {
            assertThat(resolved.referenceKey()).isEqualTo("package:7/image:11");
            assertThat(resolved.displayPath()).isEqualTo("summer.zip / front.png");
            assertThat(resolved.sourceType()).isEqualTo(AssetSourceType.ORIGINAL);
        });
    }

    @Test
    void rejectsPackageThatHasNotReachedAProcessableTerminalState() {
        AssetPackageService packages = mock(AssetPackageService.class);
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(7L);
        assetPackage.setStatus(PackageStatus.EXTRACTING);
        when(packages.require(7L)).thenReturn(assetPackage);
        DefaultAssetReferenceService service = new DefaultAssetReferenceService(
                new CanonicalAssetReferenceCodec(), packages, mock(AssetImageMapper.class));

        assertThatThrownBy(() -> service.expand(List.of("package:7"), AssetUse.INSPECT))
                .hasMessageContaining("asset reference not found");
    }

    @Test
    void rejectsImageThatBelongsToAnotherPackage() {
        AssetPackageService packages = mock(AssetPackageService.class);
        AssetImageMapper images = mock(AssetImageMapper.class);
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(7L);
        assetPackage.setStatus(PackageStatus.READY);
        when(packages.require(7L)).thenReturn(assetPackage);
        when(images.selectById(11L)).thenReturn(image(
                11L, 8L, "SKU-1", "front.png", "8/images/front.png"));
        DefaultAssetReferenceService service = new DefaultAssetReferenceService(
                new CanonicalAssetReferenceCodec(), packages, images);

        assertThatThrownBy(() -> service.resolve("package:7/image:11", AssetUse.PROCESS))
                .hasMessageContaining("asset reference not found");
    }

    private static AssetImage image(
            long id, long packageId, String skuId, String path, String storageKey) {
        AssetImage image = new AssetImage();
        image.setId(id);
        image.setPackageId(packageId);
        image.setSkuId(skuId);
        image.setOriginalPath(path);
        image.setSourceType("ORIGINAL");
        image.setPublicationStatus("READY");
        image.setMinioKey(storageKey);
        return image;
    }
}
