package com.pixflow.module.imagegen.metrics;

import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * imagegen 模块的 Micrometer 指标封装(对齐 imagegen.md §十三.五)。
 *
 * <p>6 类指标:
 * <ul>
 *   <li>{@code pixflow.imagegen.proposal} counter / result+code 标签</li>
 *   <li>{@code pixflow.imagegen.proposal.duration} timer</li>
 *   <li>{@code pixflow.imagegen.redraw} counter / outcome 标签</li>
 *   <li>{@code pixflow.imagegen.redraw.duration} timer / outcome 标签</li>
 *   <li>{@code pixflow.imagegen.payload.bytes} distribution summary / direction 标签</li>
 *   <li>{@code pixflow.imagegen.payload.hash.mismatch} counter</li>
 * </ul>
 *
 * <p>关键约束:
 * <ul>
 *   <li>不带 taskId / skuId / imageId 等业务字段,避免高基数</li>
 *   <li>{@code outcome} 与 {@code code} 标签按 {@link ImagegenErrorCode} 细分,便于定位高频错误</li>
 *   <li>prompt 内容不进指标 / 不进 trace</li>
 * </ul>
 */
@Component
public class ImagegenMetrics {

    private final MeterRegistry meterRegistry;

    /** outcome 枚举对齐 dag 的成功/失败/限流/超时/供应商/字节过大(单图隔离)。 */
    public enum RedrawOutcome {
        OK,
        FAILED,
        TIMEOUT,
        RATE_LIMITED,
        PROVIDER,
        PAYLOAD_TOO_LARGE
    }

    /** DistributionSummary 按方向(source / generated)各注册一次,缓存避免重复注册。 */
    private final ConcurrentMap<String, DistributionSummary> payloadSummaries = new ConcurrentHashMap<>();

    public ImagegenMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        // 预注册两类方向的 payload summary
        registerPayloadSummary("source");
        registerPayloadSummary("generated");
    }

    // —— proposal ——

    /** 提案结果计数(按 result=ok|reject + code 细分)。 */
    public void recordProposal(String result, ImagegenErrorCode code) {
        Counter.builder("pixflow.imagegen.proposal")
            .tag("result", result)
            .tag("code", code == null ? "UNKNOWN" : code.code())
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startProposal() {
        return Timer.start(meterRegistry);
    }

    /** 提案耗时(P99 < 30ms 是 SLO 期望)。 */
    public void stopProposal(Timer.Sample sample, boolean ok) {
        sample.stop(Timer.builder("pixflow.imagegen.proposal.duration")
            .tag("result", ok ? "ok" : "reject")
            .register(meterRegistry));
    }

    // —— redraw ——

    /** 单图重绘结果计数。 */
    public void recordRedraw(RedrawOutcome outcome) {
        Counter.builder("pixflow.imagegen.redraw")
            .tag("outcome", outcome.name().toLowerCase())
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startRedraw() {
        return Timer.start(meterRegistry);
    }

    /** 单图重绘耗时(P99 < 10s 是 SLO 期望)。 */
    public void stopRedraw(Timer.Sample sample, RedrawOutcome outcome) {
        sample.stop(Timer.builder("pixflow.imagegen.redraw.duration")
            .tag("outcome", outcome.name().toLowerCase())
            .register(meterRegistry));
    }

    // —— payload ——

    /** 字节分布(direction=source|generated)。 */
    public void recordPayloadBytes(String direction, long bytes) {
        if (bytes < 0) {
            return;
        }
        DistributionSummary summary = payloadSummaries.computeIfAbsent(direction, this::registerPayloadSummary);
        summary.record(bytes);
    }

    private DistributionSummary registerPayloadSummary(String direction) {
        return DistributionSummary.builder("pixflow.imagegen.payload.bytes")
            .tag("direction", direction)
            .baseUnit("bytes")
            .publishPercentileHistogram()
            .register(meterRegistry);
    }

    // —— hash mismatch ——

    /** payloadHash 与 token claims 不一致(确认漂移 / 重放尝试)。 */
    public void recordPayloadHashMismatch() {
        Counter.builder("pixflow.imagegen.payload.hash.mismatch")
            .register(meterRegistry)
            .increment();
    }

    /** 便捷:把一个 {@link ImagegenErrorCode} 映射到 {@link RedrawOutcome}。 */
    public static RedrawOutcome outcomeOf(ImagegenErrorCode code) {
        if (code == null) {
            return RedrawOutcome.FAILED;
        }
        return switch (code) {
            case IMAGEGEN_OUTPUT_BYTES_TOO_LARGE -> RedrawOutcome.PAYLOAD_TOO_LARGE;
            case IMAGEGEN_CONTENT_POLICY_VIOLATION -> RedrawOutcome.PROVIDER;
            default -> RedrawOutcome.FAILED;
        };
    }

    /** 暴露 {@link Duration} 直方图分位开关(对外保证百分位发布;内部已经 publishPercentileHistogram=true)。 */
    public boolean isPercentileHistogramEnabled() {
        return true;
    }
}