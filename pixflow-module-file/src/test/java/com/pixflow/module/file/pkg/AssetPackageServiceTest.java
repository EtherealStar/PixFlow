package com.pixflow.module.file.pkg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssetPackageServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");
    private final AssetPackageMapper packageMapper = org.mockito.Mockito.mock(AssetPackageMapper.class);
    private final PackageReferenceChecker referenceChecker = org.mockito.Mockito.mock(PackageReferenceChecker.class);
    private final ObjectStorage objectStorage = org.mockito.Mockito.mock(ObjectStorage.class);
    private final AssetPackageService service = new AssetPackageService(
            packageMapper,
            referenceChecker,
            objectStorage,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void physicalDeleteCleansObjectPrefixBeforeDeletingRowWhenUnreferenced() {
        AssetPackage assetPackage = packageRow(42L);
        when(packageMapper.selectById(42L)).thenReturn(assetPackage);
        when(referenceChecker.isReferenced(42L)).thenReturn(false);

        service.delete(42L);

        verify(objectStorage).deleteByPrefix(BucketType.PACKAGES, "42/");
        verify(packageMapper).deleteById(42L);
    }

    @Test
    void referencedPackageIsSoftDeletedAndObjectsAreKept() {
        AssetPackage assetPackage = packageRow(42L);
        when(packageMapper.selectById(42L)).thenReturn(assetPackage);
        when(referenceChecker.isReferenced(42L)).thenReturn(true);

        service.delete(42L);

        ArgumentCaptor<AssetPackage> updateCaptor = ArgumentCaptor.forClass(AssetPackage.class);
        verify(packageMapper).updateById(updateCaptor.capture());
        AssetPackage update = updateCaptor.getValue();
        assertThat(update.getId()).isEqualTo(42L);
        assertThat(update.getDeletedAt()).isEqualTo(NOW);
        assertThat(update.getUpdatedAt()).isEqualTo(NOW);
        verify(objectStorage, never()).deleteByPrefix(eq(BucketType.PACKAGES), eq("42/"));
        verify(packageMapper, never()).deleteById(42L);
    }

    @Test
    void missingPackageThrowsDomainException() {
        when(packageMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.require(404L))
                .isInstanceOf(PixFlowException.class)
                .hasMessageContaining("package not found");
    }

    private static AssetPackage packageRow(long id) {
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(id);
        assetPackage.setStatus(PackageStatus.READY);
        return assetPackage;
    }
}
