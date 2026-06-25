package com.etherealstar.pixflow.module.file.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.support.InMemoryZips;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * zip-bomb 阈值防护属性测试（任务 2.7）。
 *
 * <p>Feature: pixflow, Property 3: For any 解压场景，当累计解压文件总大小超过阈值（默认 2 GB）
 * 或文件总数超过阈值（默认 2000）时，Asset_Manager 应终止解压并返回压缩包异常错误；未越界时不因
 * 该校验终止。
 * Validates: Requirements 1.4
 *
 * <p>为在毫秒级完成大量迭代，本测试将阈值调小（文件数 ≤ {@value #MAX_COUNT}，累计大小 ≤
 * {@value #MAX_SIZE} 字节），并以程序化生成的内存 zip 覆盖阈值两侧。
 */
class ZipBombThresholdPropertyTest {

    private static final int MAX_COUNT = 5;
    private static final int MAX_SIZE = 1000;

    private static ZipExtractor extractor() {
        AssetProperties props = new AssetProperties();
        props.setExtractedMaxCount(MAX_COUNT);
        props.setExtractedMaxSize(MAX_SIZE);
        return new ZipExtractor(props);
    }

    private static List<String> names(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add("file" + i + ".bin");
        }
        return names;
    }

    @Property(tries = 200)
    void exceedingFileCountTriggersBomb(@ForAll @IntRange(min = 0, max = 12) int fileCount) {
        // 每个条目仅 1 字节，确保只有「文件数」越界，累计大小不越界
        byte[] zip = InMemoryZips.zipWithEntries(names(fileCount), 1);
        ZipExtractor extractor = extractor();
        AtomicInteger consumed = new AtomicInteger();

        if (fileCount > MAX_COUNT) {
            assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(zip),
                    (path, content) -> consumed.incrementAndGet()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ZIP_BOMB);
        } else {
            assertThatCode(() -> extractor.extract(new ByteArrayInputStream(zip),
                    (path, content) -> consumed.incrementAndGet()))
                    .doesNotThrowAnyException();
            assertThat(consumed.get()).isEqualTo(fileCount);
        }
    }

    @Property(tries = 200)
    void exceedingTotalSizeTriggersBomb(@ForAll @IntRange(min = 0, max = 2000) int entrySize) {
        // 单条目 zip，仅「累计大小」可能越界，文件数恒为 1（不越界）
        byte[] zip = InMemoryZips.singleEntryZip("big.bin", entrySize);
        ZipExtractor extractor = extractor();
        AtomicInteger consumed = new AtomicInteger();

        if (entrySize > MAX_SIZE) {
            assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(zip),
                    (path, content) -> consumed.incrementAndGet()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ZIP_BOMB);
        } else {
            assertThatCode(() -> extractor.extract(new ByteArrayInputStream(zip),
                    (path, content) -> consumed.incrementAndGet()))
                    .doesNotThrowAnyException();
            assertThat(consumed.get()).isEqualTo(1);
        }
    }
}
