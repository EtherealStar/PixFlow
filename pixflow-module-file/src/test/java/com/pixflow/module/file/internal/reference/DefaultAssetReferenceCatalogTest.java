package com.pixflow.module.file.internal.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.api.AssetReferenceSource;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.PackageStatus;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class DefaultAssetReferenceCatalogTest {
    private final AssetPackageMapper packages = mock(AssetPackageMapper.class);

    private final AssetImageMapper images = mock(AssetImageMapper.class);

    private final DefaultAssetReferenceCatalog catalog = new DefaultAssetReferenceCatalog(
            new CanonicalAssetReferenceCodec(), packages, images);

    @Test
    void exactSkuExclusionRunsBeforePagination() {
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(7L);
        assetPackage.setName("summer.zip");
        assetPackage.setStatus(PackageStatus.READY);
        when(packages.selectById(7L)).thenReturn(assetPackage);
        when(images.countReadyOriginalSkus(7L, List.of("B"))).thenReturn(2L);
        when(images.listReadyOriginalSkus(7L, List.of("B"), 0, 2))
                .thenReturn(List.of("A", "C"));

        var page = catalog.list(null, "package:7", null,
                1, 2, List.of("package:7/sku:B"));

        assertThat(page.records()).extracting(item -> item.referenceKey())
                .containsExactly("package:7/sku:A", "package:7/sku:C");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void imageCannotBeUsedAsBrowseParent() {
        assertThatThrownBy(() -> catalog.list(null,
                "package:7/image:9", null, 1, 50, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be a parent");
    }

    @Test
    void rejectsConflictingBrowseSelectors() {
        assertThatThrownBy(() -> catalog.list(
                AssetReferenceSource.MATERIALS, "package:7", null, 1, 50, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedOutputsUseFileLineageAndExcludeBeforePagination() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "catalog-test"),
                AssetImage.class);
        AssetImage image = new AssetImage();
        image.setId(19L);
        image.setPackageId(7L);
        image.setSkuId("SKU-A");
        image.setSourceType(AssetSourceType.GENERATED.name());
        image.setDisplayName("result.png");
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(7L);
        assetPackage.setName("summer.zip");
        when(packages.selectById(7L)).thenReturn(assetPackage);
        when(images.selectPage(any(IPage.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> {
                    IPage<AssetImage> requestedPage = invocation.getArgument(0);
                    LambdaQueryWrapper<AssetImage> query = invocation.getArgument(1);
                    // 查询条件和 exact exclusion 必须在数据库分页前同时生效。
                    assertThat(query.getSqlSegment())
                            .contains("source_type", "publication_status", "source_task_id",
                                    "deletion_status", "id NOT IN");
                    assertThat(query.getParamNameValuePairs().values())
                            .contains(AssetSourceType.GENERATED.name(), "READY", 42L, 20L);
                    return new Page<AssetImage>(requestedPage.getCurrent(), requestedPage.getSize(), 1)
                            .setRecords(List.of(image));
                });

        var result = catalog.listGeneratedByTaskId(
                42L, 2, 10, List.of("package:7/image:20"));

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).singleElement().satisfies(candidate -> {
            assertThat(candidate.referenceKey()).isEqualTo("package:7/image:19");
            assertThat(candidate.sourceType()).isEqualTo(AssetSourceType.GENERATED);
            assertThat(candidate.sourceGroup()).isNull();
            assertThat(candidate.displayPath()).isEqualTo("summer.zip / SKU-A / result.png");
        });
    }
}
