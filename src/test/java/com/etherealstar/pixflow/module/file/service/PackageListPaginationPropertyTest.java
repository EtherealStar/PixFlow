package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.common.web.Pagination;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * 素材包列表分页正确性与参数校验属性测试（任务 6.5）。
 *
 * <p>Feature: pixflow, Property 13: 分页正确性与参数校验——当 {@code page ≥ 1} 且 {@code 1 ≤ size ≤ 100}
 * 时返回条数应 ≤ {@code size}、返回的 {@code total} 等于总记录数、且回显的分页参数与请求一致；当 {@code page}
 * 或 {@code size} 超出允许范围时应以 {@link ErrorCode#INVALID_PAGINATION} 拒绝且不触达查询。
 *
 * <p>Validates: Requirements 4.4, 4.5, 13.1, 13.2
 */
class PackageListPaginationPropertyTest {

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
        void stubPage(long page, long size, long total) {
            // 当前页记录数不超过 size（取 min(size, 3) 个占位记录）
            int recordCount = (int) Math.min(size, 3L);
            List<AssetPackage> records = new ArrayList<>();
            for (int i = 0; i < recordCount; i++) {
                AssetPackage pkg = new AssetPackage();
                pkg.setId((long) i + 1);
                pkg.setName("pkg-" + i);
                pkg.setSize(100L);
                pkg.setImageCount(1);
                pkg.setStatus(1);
                pkg.setCreatedAt(LocalDateTime.now());
                records.add(pkg);
            }
            Page<AssetPackage> result = new Page<>(page, size);
            result.setRecords(records);
            result.setTotal(total);
            when(packageMapper.selectPage(any(), any(QueryWrapper.class))).thenReturn(result);
        }
    }

    @Provide
    Arbitrary<Long> pages() {
        return Arbitraries.longs().between(-5, 1000);
    }

    @Provide
    Arbitrary<Long> sizes() {
        return Arbitraries.longs().between(-5, 200);
    }

    private static boolean legal(long page, long size) {
        return page >= Pagination.MIN_PAGE && size >= Pagination.MIN_SIZE && size <= Pagination.MAX_SIZE;
    }

    @Property(tries = 500)
    void legalParamsQueryAndEchoPagination(@ForAll("pages") long page, @ForAll("sizes") long size) {
        net.jqwik.api.Assume.that(legal(page, size));

        Fixture f = new Fixture();
        long total = 12345L;
        f.stubPage(page, size, total);

        PageResponse<PackageListItem> response = f.service.list(page, size, "created_at", "desc");

        assertThat(response.page()).isEqualTo(page);
        assertThat(response.size()).isEqualTo(size);
        assertThat(response.total()).isEqualTo(total);
        // 返回条数不超过 size
        assertThat((long) response.records().size()).isLessThanOrEqualTo(size);
    }

    @Property(tries = 500)
    void illegalParamsAreRejectedWithoutQuery(@ForAll("pages") long page, @ForAll("sizes") long size) {
        net.jqwik.api.Assume.that(!legal(page, size));

        Fixture f = new Fixture();

        assertThatThrownBy(() -> f.service.list(page, size, "created_at", "desc"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PAGINATION);

        // 非法分页在查询前即被拒绝，不应触达 mapper
        verify(f.packageMapper, never()).selectPage(any(), any(QueryWrapper.class));
    }

    @Test
    void nullParamsResolveToDefaults() {
        Fixture f = new Fixture();
        f.stubPage(Pagination.DEFAULT_PAGE, Pagination.DEFAULT_SIZE, 0L);

        PageResponse<PackageListItem> response = f.service.list(null, null, null, null);

        assertThat(response.page()).isEqualTo(Pagination.DEFAULT_PAGE);
        assertThat(response.size()).isEqualTo(Pagination.DEFAULT_SIZE);
    }

    @Test
    void boundaryValuesAreAccepted() {
        // 边界：page=1、size=1 与 size=100 均合法
        assertThat(Pagination.of(1L, 1L).size()).isEqualTo(1L);
        assertThat(Pagination.of(1L, Pagination.MAX_SIZE).size()).isEqualTo(Pagination.MAX_SIZE);
        // 边界外：page=0、size=0、size=101 均非法
        for (long[] bad : new long[][]{{0, 20}, {1, 0}, {1, 101}}) {
            assertThatThrownBy(() -> Pagination.of(bad[0], bad[1]))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PAGINATION);
        }
    }
}
