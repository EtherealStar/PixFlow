package com.etherealstar.pixflow.module.file.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * PackageScanResult 状态二分属性测试（任务 2.6）。
 *
 * <p>Feature: pixflow, Property 2: 对任意 zip，当其成功识别图片数 > 0 时素材包 status 应为就绪（1），
 * 当成功识别图片数 == 0 时 status 应为解析失败（2）。
 * Validates: Requirements 1.9, 1.10
 */
class PackageScanResultPropertyTest {

    @Provide
    Arbitrary<String> paths() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20);
    }

    @Provide
    Arbitrary<SkippedFile> skippedFiles() {
        return paths().map(name -> new SkippedFile(name, "skip-reason"));
    }

    @Property(tries = 300)
    void statusIsBinaryOnRecognizedCount(
            @ForAll("paths") @Size(max = 30) List<String> recognized,
            @ForAll("skippedFiles") @Size(max = 30) List<SkippedFile> skipped) {

        PackageScanResult result = PackageScanResult.of(recognized, skipped);

        // image_count 恒等于成功识别图片数
        assertThat(result.getImageCount()).isEqualTo(recognized.size());

        if (recognized.isEmpty()) {
            assertThat(result.getStatus()).isEqualTo(PackageStatus.PARSE_FAILED);
            assertThat(result.getFailureReason()).isNotNull();
        } else {
            assertThat(result.getStatus()).isEqualTo(PackageStatus.READY);
            assertThat(result.getFailureReason()).isNull();
        }
    }
}
