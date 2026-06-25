package com.etherealstar.pixflow.module.file.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * 素材包状态二分属性测试（任务 2.6）。
 *
 * <p>Feature: pixflow, Property 2: For any zip，当其成功识别图片数 &gt; 0 时素材包 {@code status}
 * 应为就绪（1），当成功识别图片数 == 0 时 {@code status} 应为解析失败（2）。
 * Validates: Requirements 1.9, 1.10
 */
class PackageStatusPropertyTest {

    @Provide
    Arbitrary<List<String>> recognizedImages() {
        return Arbitraries.integers().between(0, 50).map(count -> {
            List<String> images = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                images.add("dir/img" + i + ".jpg");
            }
            return images;
        });
    }

    @Provide
    Arbitrary<List<SkippedFile>> skippedFiles() {
        return Arbitraries.integers().between(0, 20).map(n -> {
            List<SkippedFile> skipped = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                skipped.add(new SkippedFile("skip" + i + ".txt", "原因" + i));
            }
            return skipped;
        });
    }

    @Property(tries = 300)
    void statusIsBinaryByImageCount(@ForAll("recognizedImages") List<String> images,
                                    @ForAll("skippedFiles") List<SkippedFile> skipped) {
        PackageScanResult result = PackageScanResult.of(images, skipped);

        assertThat(result.getImageCount()).isEqualTo(images.size());
        if (images.isEmpty()) {
            assertThat(result.getStatus()).isEqualTo(PackageStatus.PARSE_FAILED);
            assertThat(result.getFailureReason()).isNotNull();
        } else {
            assertThat(result.getStatus()).isEqualTo(PackageStatus.READY);
            assertThat(result.getFailureReason()).isNull();
        }
        // 状态恒为二分之一，绝不取解析中（0）等其它值
        assertThat(result.getStatus())
                .isIn(PackageStatus.READY, PackageStatus.PARSE_FAILED);
    }

    @Test
    void emptyIsParseFailedNonEmptyIsReady() {
        assertThat(PackageScanResult.of(List.of(), List.of()).getStatus())
                .isEqualTo(PackageStatus.PARSE_FAILED);
        assertThat(PackageScanResult.of(List.of("a.jpg"), List.of()).getStatus())
                .isEqualTo(PackageStatus.READY);
    }
}
