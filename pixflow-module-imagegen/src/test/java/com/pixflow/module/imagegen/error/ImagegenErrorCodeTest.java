package com.pixflow.module.imagegen.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.RecoveryHint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImagegenErrorCode 单测(对齐 imagegen.md §十二 / §十六.8)。
 *
 * <p>校验 9 条错误码唯一、category 非空、按 category 推断的 recovery 方向与设计预期一致。
 */
class ImagegenErrorCodeTest {

    @Test
    @DisplayName("9 条错误码全部枚举且唯一")
    void enumHas9UniqueCodes() {
        ImagegenErrorCode[] values = ImagegenErrorCode.values();
        assertThat(values).hasSize(9);
        long distinct = java.util.Arrays.stream(values).map(Enum::name).distinct().count();
        assertThat(distinct).isEqualTo(9);
    }

    @Test
    @DisplayName("每个错误码都绑定了一个 category,不为 null")
    void allCodesHaveCategory() {
        for (ImagegenErrorCode code : ImagegenErrorCode.values()) {
            assertThat(code.category()).as("category for %s", code).isNotNull();
            assertThat(code.code()).as("code string for %s", code).isEqualTo(code.name());
        }
    }

    @Test
    @DisplayName("提案侧 / 执行侧错误码的 recovery 倾向与 imagegen.md §十二表格一致")
    void recoveryHintsAlignWithDesign() {
        // 提案侧校验错:recovery 应为 SKIP(VALIDATION/NOT_FOUND/BUSINESS_RULE)
        assertThat(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND.category())
            .isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID.category())
            .isEqualTo(ErrorCategory.VALIDATION);
        // 执行侧字节 / 存储 / 内容审查
        assertThat(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE.category())
            .isEqualTo(ErrorCategory.VALIDATION);
        assertThat(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED.category())
            .isEqualTo(ErrorCategory.STORAGE);
        assertThat(ImagegenErrorCode.IMAGEGEN_CONTENT_POLICY_VIOLATION.category())
            .isEqualTo(ErrorCategory.PROVIDER);

        // 确认侧 TERMINATE:payloadHash / planNotFound
        assertThat(ImagegenErrorCode.IMAGEGEN_PAYLOAD_HASH_MISMATCH.category())
            .isEqualTo(ErrorCategory.VALIDATION);
        assertThat(ImagegenErrorCode.IMAGEGEN_PLAN_NOT_FOUND.category())
            .isEqualTo(ErrorCategory.NOT_FOUND);
    }

    @Test
    @DisplayName("错误码的 code() 字符串可作为 messageKey 直接使用")
    void codeStringRoundTrips() {
        assertThat(ImagegenErrorCode.IMAGEGEN_PAYLOAD_HASH_MISMATCH.code())
            .isEqualTo("IMAGEGEN_PAYLOAD_HASH_MISMATCH");
        assertThat(ImagegenErrorCode.IMAGEGEN_PAYLOAD_HASH_MISMATCH.messageKey())
            .isEqualTo("IMAGEGEN_PAYLOAD_HASH_MISMATCH");
    }

    @Test
    @DisplayName("RecoveryHint 分类核对(用于执行侧失败隔离语义)")
    void recoveryHintsCoverIsolation() {
        // 执行期 SKIP 类:字节过大(单图隔离) / 内容审查(单图隔离)
        RecoveryHint bytesRecovery = ErrorCategory.VALIDATION.defaultRecovery();
        RecoveryHint providerRecovery = ErrorCategory.PROVIDER.defaultRecovery();
        // VALIDATION 默认 TERMINATE,但 IMAGEGEN_OUTPUT_BYTES_TOO_LARGE 在业务上是单图隔离 (SKIP),
        // executor 在抛异常时通过 withRecoveryOverride(SKIP) 覆盖。
        // 本单测仅校验 category 推断方向正确,recovery 由抛方显式覆盖。
        assertThat(bytesRecovery).isEqualTo(RecoveryHint.TERMINATE);
        assertThat(providerRecovery).isEqualTo(RecoveryHint.RETRY);
    }
}
