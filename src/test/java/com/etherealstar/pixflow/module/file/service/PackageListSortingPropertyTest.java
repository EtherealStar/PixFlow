package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.copy.CopyDocumentParser;
import com.etherealstar.pixflow.module.file.dto.PackageListItem;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.extract.ZipExtractor;
import com.etherealstar.pixflow.module.file.image.ImageValidator;
import com.etherealstar.pixflow.module.file.mapper.AssetCopyMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import java.util.Collections;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 素材包列表排序属性测试（任务 6.4）。
 *
 * <p>Feature: pixflow, Property 12: 列表排序正确性——对任意排序参数（字段 ∈ {created_at, size, name}，
 * 顺序 ∈ {asc, desc}），返回列表应按指定字段与顺序有序；未指定（或非法）排序字段时默认按 {@code created_at}，
 * 未指定（或非法）顺序时默认降序。
 *
 * <p>排序由数据库层的 {@code ORDER BY} 承担，本测试以参考实现（oracle）方式验证服务层据入参构造的
 * {@link QueryWrapper} 的排序列与方向正确，并对未指定/非法入参回退到默认值。
 *
 * <p>Validates: Requirements 4.2, 4.3
 */
class PackageListSortingPropertyTest {

    /** 持有一套 AssetPackageService 及被 mock 的依赖，便于每次迭代独立构造。 */
    private static final class Fixture {
        final AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
        final AssetPackageService service;

        Fixture() {
            this.service = new AssetPackageService(
                    packageMapper,
                    mock(AssetImageMapper.class),
                    mock(AssetCopyMapper.class),
                    mock(StorageService.class),
                    mock(ZipExtractor.class),
                    mock(ImageValidator.class),
                    mock(CopyDocumentParser.class),
                    mock(AssetProperties.class),
                    mock(PackageDeleter.class));
        }

        @SuppressWarnings("unchecked")
        void stubEmptyPage() {
            Page<AssetPackage> empty = new Page<>(1, 20);
            empty.setRecords(Collections.emptyList());
            empty.setTotal(0);
            when(packageMapper.selectPage(any(), any(QueryWrapper.class))).thenReturn(empty);
        }
    }

    @Provide
    Arbitrary<String> sortFields() {
        return Arbitraries.of("created_at", "size", "name");
    }

    @Provide
    Arbitrary<String> orders() {
        return Arbitraries.of("asc", "desc", "ASC", "DESC");
    }

    /** 含合法字段、空值与非法字段的混合输入，用于验证默认回退。 */
    @Provide
    Arbitrary<String> sortFieldsWithNoise() {
        return Arbitraries.of("created_at", "size", "name", "id", "DROP TABLE", "", "  CREATED_AT  ", null);
    }

    @Provide
    Arbitrary<String> ordersWithNoise() {
        return Arbitraries.of("asc", "desc", "ASC", " desc ", "ascending", "", "xyz", null);
    }

    @Property(tries = 300)
    @SuppressWarnings("unchecked")
    void validSortParamsProduceMatchingOrderBy(@ForAll("sortFields") String sortBy,
                                               @ForAll("orders") String order) {
        Fixture f = new Fixture();
        f.stubEmptyPage();

        f.service.list(1L, 20L, sortBy, order);

        QueryWrapper<AssetPackage> wrapper = captureWrapper(f);
        String segment = wrapper.getSqlSegment().toUpperCase(Locale.ROOT);

        boolean asc = "asc".equalsIgnoreCase(order.trim());
        String dir = asc ? "ASC" : "DESC";
        // 主排序列与方向应与入参一致，且以 id 作为稳定次级排序键（同方向）
        assertThat(segment).contains(sortBy.toUpperCase(Locale.ROOT) + " " + dir);
        assertThat(segment).contains("ID " + dir);
        assertThat(segment.trim()).startsWith("ORDER BY");
    }

    @Property(tries = 300)
    @SuppressWarnings("unchecked")
    void unspecifiedOrIllegalParamsFallBackToCreatedAtDesc(
            @ForAll("sortFieldsWithNoise") String sortBy,
            @ForAll("ordersWithNoise") String order) {
        Fixture f = new Fixture();
        f.stubEmptyPage();

        f.service.list(1L, 20L, sortBy, order);

        QueryWrapper<AssetPackage> wrapper = captureWrapper(f);
        String segment = wrapper.getSqlSegment().toUpperCase(Locale.ROOT);

        // oracle：仅 {created_at,size,name}（去空白、不区分大小写）为合法列，否则回退 created_at
        String normalized = sortBy == null ? null : sortBy.trim().toLowerCase(Locale.ROOT);
        boolean legalColumn = "created_at".equals(normalized)
                || "size".equals(normalized)
                || "name".equals(normalized);
        String expectedColumn = legalColumn ? normalized.toUpperCase(Locale.ROOT) : "CREATED_AT";
        // oracle：仅 "asc"（去空白、不区分大小写）为升序，否则默认降序
        boolean asc = order != null && "asc".equalsIgnoreCase(order.trim());
        String dir = asc ? "ASC" : "DESC";

        assertThat(segment).contains(expectedColumn + " " + dir);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullParamsDefaultToCreatedAtDescending() {
        Fixture f = new Fixture();
        f.stubEmptyPage();

        PageResponse<PackageListItem> response = f.service.list(1L, 20L, null, null);
        assertThat(response).isNotNull();

        QueryWrapper<AssetPackage> wrapper = captureWrapper(f);
        String segment = wrapper.getSqlSegment().toUpperCase(Locale.ROOT);
        assertThat(segment).contains("CREATED_AT DESC");
        assertThat(segment).contains("ID DESC");
    }

    @SuppressWarnings("unchecked")
    private static QueryWrapper<AssetPackage> captureWrapper(Fixture f) {
        ArgumentCaptor<QueryWrapper<AssetPackage>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(f.packageMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }
}
