package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.support.AssetServiceFixture;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.springframework.mock.web.MockMultipartFile;

/**
 * zip 体积上限属性测试（任务 2.9）。
 *
 * <p>Feature: pixflow, Property 5: For any 上传 zip 体积值，当其超过配置上限（默认 500 MB）时
 * 应被拒绝且错误信息包含上限值，未超过时不因体积校验被拒。
 * Validates: Requirements 1.3
 *
 * <p>为避免分配巨大字节数组，本测试将上限调小至 {@value #LIMIT} 字节，并在其两侧取值。
 */
class ZipSizeLimitPropertyTest {

    private static final int LIMIT = 1024;

    private static AssetServiceFixture fixture() {
        AssetProperties props = new AssetProperties();
        props.setZipMaxSize(LIMIT);
        return new AssetServiceFixture(props, content -> true);
    }

    private static MockMultipartFile upload(byte[] content) {
        return new MockMultipartFile("zip_file", "a.zip", "application/zip", content);
    }

    @Property(tries = 200)
    void sizeBeyondLimitIsRejectedWithLimitInMessage(
            @ForAll @IntRange(min = 1025, max = 8192) int size) {
        AssetServiceFixture f = fixture();
        byte[] content = new byte[size];
        f.withZip(content);

        BusinessException ex = catchThrowableOfType(
                () -> f.service.upload(upload(content), null), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ASSET_ZIP_TOO_LARGE);
        // 错误信息须包含上限值（需求 1.3）
        assertThat(ex.getMessage()).contains(String.valueOf(LIMIT));
    }

    @Property(tries = 200)
    void sizeWithinLimitIsNotRejectedBySizeCheck(
            @ForAll @IntRange(min = 1, max = LIMIT) int size) {
        AssetServiceFixture f = fixture();
        // 全零内容不是合法 zip，会在签名阶段以 ASSET_ZIP_INVALID 失败，
        // 但绝不应因体积校验（ASSET_ZIP_TOO_LARGE）被拒。
        byte[] content = new byte[size];
        f.withZip(content);

        BusinessException ex = catchThrowableOfType(
                () -> f.service.upload(upload(content), null), BusinessException.class);

        if (ex != null) {
            assertThat(ex.getErrorCode()).isNotEqualTo(ErrorCode.ASSET_ZIP_TOO_LARGE);
        }
    }
}
