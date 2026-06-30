package com.pixflow.harness.loop;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.resilience.ModelRetryRunner;
import com.pixflow.infra.ai.resilience.RetryPolicy;
import java.time.Duration;
import reactor.core.publisher.Flux;

/**
 * 测试用 {@link ModelRetryRunner} 替身。
 *
 * <p>由于 {@link ModelRetryRunner} 是 final 类不可继承，本类是 {@code new ModelRetryRunner(RetryPolicy(0))}
 * 的轻量工厂：返回一个 maxRetries=0（不重试）的 runner，用于把测试中所有错误注入都来自
 * 真实 {@link com.pixflow.infra.ai.chat.ChatModelClient#stream} 的 Flux.error(...)。
 *
 * <p>{@link #isContextLimit(Throwable)} 帮助测试断言错误类别。
 */
public final class FakeModelRetryRunner {

    private FakeModelRetryRunner() {
    }

    public static ModelRetryRunner noRetryRunner() {
        return new ModelRetryRunner(new RetryPolicy(0, Duration.ZERO, Duration.ZERO, 0.0));
    }

    public static Flux<ChatStreamEvent> errorFlux(PixFlowException error) {
        return Flux.error(error);
    }

    public static PixFlowException contextLimit(String message) {
        return new PixFlowException(
                com.pixflow.common.error.CommonErrorCode.CONTEXT_LIMIT_EXCEEDED, message);
    }

    public static boolean isContextLimit(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof PixFlowException pf) {
                return pf.category() == ErrorCategory.CONTEXT_LIMIT;
            }
            cur = cur.getCause();
        }
        return false;
    }
}