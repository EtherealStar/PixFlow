package com.etherealstar.pixflow.module.dag.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * 单支路失败隔离属性测试（任务 12.16）。
 *
 * <p>Feature: pixflow, Property 32: 单支路失败隔离——当某条「图片 × 支路」工作单元失败时，
 * {@link FailureIsolator} 将该结果置为失败（status=2）、记录截断至上限的 {@code error_msg}、
 * 并清空不完整的 {@code output_path}（不保留半成品）。失败被隔离在单条结果内，不修改其它字段
 * 标识（taskId/imageId/branchId/skuId 保持不变），从而不影响其余支路/图片/SKU 的处理。
 * Validates: Requirements 10.7, 11.1, 11.2, 9.4
 *
 * <p>本测试为纯逻辑测试，不涉及真实图片、文案生成或外部 API。
 */
class FailureIsolationPropertyTest {

    private FailureIsolator isolatorWithMax(int errorMsgMaxLength) {
        EngineProperties props = new EngineProperties();
        props.setErrorMsgMaxLength(errorMsgMaxLength);
        return new FailureIsolator(props);
    }

    private ProcessResult freshResult() {
        ProcessResult r = new ProcessResult();
        r.setTaskId(7L);
        r.setImageId(42L);
        r.setSkuId("SKU123");
        r.setBranchId("branch-3");
        r.setStatus(0);
        r.setOutputPath("results/7/partial-output.png"); // 不完整产物，须被清空
        return r;
    }

    /** 非空白错误信息，长度足以触发截断的各种情形。 */
    @Provide
    Arbitrary<String> messages() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(3000);
    }

    @Property(tries = 200)
    void failureMarksResultFailedAndClearsOutput(
            @ForAll("messages") String message,
            @ForAll @IntRange(min = 1, max = 2000) int maxLength) {

        FailureIsolator isolator = isolatorWithMax(maxLength);
        ProcessResult result = freshResult();

        isolator.markFailed(result, new RuntimeException(message));

        // 状态置为失败，且不保留不完整产物
        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getOutputPath()).isNull();

        // error_msg 截断至上限，且为原始信息的前缀
        assertThat(result.getErrorMsg()).isNotNull();
        assertThat(result.getErrorMsg().length()).isLessThanOrEqualTo(maxLength);
        assertThat(message).startsWith(result.getErrorMsg());
        String expected = message.length() > maxLength ? message.substring(0, maxLength) : message;
        assertThat(result.getErrorMsg()).isEqualTo(expected);

        // 隔离不破坏标识字段（保证其余支路/图片/SKU 不受影响）
        assertThat(result.getTaskId()).isEqualTo(7L);
        assertThat(result.getImageId()).isEqualTo(42L);
        assertThat(result.getSkuId()).isEqualTo("SKU123");
        assertThat(result.getBranchId()).isEqualTo("branch-3");
    }

    @Property(tries = 100)
    void blankOrNullMessageFallsBackToExceptionType(
            @ForAll @IntRange(min = 0, max = 5) int spaceCount) {
        FailureIsolator isolator = isolatorWithMax(1000);

        ProcessResult nullMsg = freshResult();
        isolator.markFailed(nullMsg, new IllegalStateException((String) null));
        assertThat(nullMsg.getStatus()).isEqualTo(2);
        assertThat(nullMsg.getErrorMsg()).isEqualTo("IllegalStateException");
        assertThat(nullMsg.getOutputPath()).isNull();

        ProcessResult blankMsg = freshResult();
        isolator.markFailed(blankMsg, new IllegalStateException(" ".repeat(spaceCount)));
        // 空白信息回退为异常类型名
        assertThat(blankMsg.getErrorMsg()).isEqualTo("IllegalStateException");
    }

    @Test
    void nullThrowableIsHandledGracefully() {
        FailureIsolator isolator = isolatorWithMax(1000);
        ProcessResult result = freshResult();

        isolator.markFailed(result, null);

        assertThat(result.getStatus()).isEqualTo(2);
        assertThat(result.getOutputPath()).isNull();
        assertThat(result.getErrorMsg()).isEqualTo("未知错误");
    }
}
