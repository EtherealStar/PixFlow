package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.support.AssetServiceFixture;
import com.etherealstar.pixflow.module.file.support.InMemoryZips;
import java.util.Arrays;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 非法 zip 拒绝属性测试（任务 2.8）。
 *
 * <p>Feature: pixflow, Property 4: For any 非合法 zip 字节流（含空文件、随机字节、截断 zip），
 * Asset_Manager 应拒绝上传并返回 zip 文件无效的错误。
 * Validates: Requirements 1.2
 */
class IllegalZipRejectionPropertyTest {

    /** 解码器在本测试中永不被触达；非法 zip 在解压/签名阶段即被拒。 */
    private static AssetServiceFixture fixture() {
        return new AssetServiceFixture(new AssetProperties(), content -> true);
    }

    private static MockMultipartFile zipUpload(byte[] content) {
        return new MockMultipartFile("zip_file", "a.zip", "application/zip", content);
    }

    @Test
    void emptyFileIsRejected() {
        AssetServiceFixture f = fixture();
        assertThatThrownBy(() -> f.service.upload(zipUpload(new byte[0]), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET_ZIP_INVALID);
    }

    @Property(tries = 200)
    void randomBytesAreRejected(@ForAll @Size(min = 1, max = 64) byte[] content) {
        // 强制首字节不等于 zip 本地文件头首字节（0x50），保证签名校验直接失败
        byte[] bytes = content.clone();
        bytes[0] = 0x00;
        AssetServiceFixture f = fixture();
        f.withZip(bytes);

        assertThatThrownBy(() -> f.service.upload(zipUpload(bytes), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET_ZIP_INVALID);
    }

    @Property(tries = 100)
    void truncatedZipIsRejected(@ForAll @IntRange(min = 30, max = 80) int prefixLen) {
        // 合法 zip 起始 4 字节为 PK\u0003\u0004，可通过签名校验。
        // 本地文件头固定 30 字节，其后为文件名字节；将截断点落在「文件名读取区间」内
        // （prefixLen ∈ [30, 30 + nameLen)），可确保 ZipInputStream 在读取条目名时遇到 EOF 抛错，
        // 从而被 ZipExtractor 归类为非法 zip（ASSET_ZIP_INVALID）。
        String longName = "nested/dir/" + "a".repeat(40) + ".jpg"; // name 长度 55 > 80-30
        byte[] validZip = InMemoryZips.singleEntryZip(longName, 200);
        byte[] truncated = Arrays.copyOf(validZip, Math.min(prefixLen, validZip.length));
        AssetServiceFixture f = fixture();
        f.withZip(truncated);

        assertThatThrownBy(() -> f.service.upload(zipUpload(truncated), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET_ZIP_INVALID);
    }

    @Test
    void validZipFirstBytesAreLocalFileHeaderSignature() {
        byte[] zip = InMemoryZips.singleEntryZip("a.jpg", 10);
        assertThat(new byte[] {zip[0], zip[1], zip[2], zip[3]})
                .containsExactly(0x50, 0x4B, 0x03, 0x04);
    }
}
