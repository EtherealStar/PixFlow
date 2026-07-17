package com.pixflow.module.imagegen.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImagegenMetrics 单测(对齐 imagegen.md §十三.5)。
 *
 * <p>覆盖 6 类指标:counter / timer / distribution summary 全部能被 record
 * 且能在 SimpleMeterRegistry 中查到;并校验 outcome 与 code 标签按设计细分。
 */
class ImagegenMetricsTest {

    private SimpleMeterRegistry registry;
    private ImagegenMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ImagegenMetrics(registry);
    }

    @Test
    @DisplayName("proposal counter:按 result + code 标签")
    void proposalCounter_tagsAlign() {
        metrics.recordProposal("ok", null);
        metrics.recordProposal("reject", ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID);
        Counter ok = registry.find("pixflow.imagegen.proposal").tag("result", "ok").tag("code", "UNKNOWN").counter();
        Counter reject = registry.find("pixflow.imagegen.proposal").tag("result", "reject")
            .tag("code", "IMAGEGEN_PROMPT_INVALID").counter();
        assertThat(ok).isNotNull();
        assertThat(ok.count()).isEqualTo(1.0d);
        assertThat(reject.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("proposal.duration timer:按 result 标签")
    void proposalDuration_recordsWithResultTag() throws Exception {
        Timer.Sample s1 = metrics.startProposal();
        Thread.sleep(5);
        metrics.stopProposal(s1, true);

        Timer.Sample s2 = metrics.startProposal();
        Thread.sleep(5);
        metrics.stopProposal(s2, false);

        assertThat(registry.find("pixflow.imagegen.proposal.duration").tag("result", "ok").timer().count())
            .isEqualTo(1L);
        assertThat(registry.find("pixflow.imagegen.proposal.duration").tag("result", "reject").timer().count())
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("redraw counter:outcome 6 枚举值与 imagegen.md §十三.5 对齐")
    void redrawCounter_outcomesAlign() {
        for (ImagegenMetrics.RedrawOutcome o : ImagegenMetrics.RedrawOutcome.values()) {
            metrics.recordRedraw(o);
        }
        for (ImagegenMetrics.RedrawOutcome o : ImagegenMetrics.RedrawOutcome.values()) {
            Counter c = registry.find("pixflow.imagegen.redraw").tag("outcome", o.name().toLowerCase()).counter();
            assertThat(c).as("counter for outcome %s", o).isNotNull();
            assertThat(c.count()).isEqualTo(1.0d);
        }
    }

    @Test
    @DisplayName("redraw.duration timer:按 outcome 标签")
    void redrawDuration_recordsWithOutcomeTag() {
        Timer.Sample s = metrics.startRedraw();
        metrics.stopRedraw(s, ImagegenMetrics.RedrawOutcome.OK);
        assertThat(registry.find("pixflow.imagegen.redraw.duration")
            .tag("outcome", "ok").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("payload.bytes distribution summary:direction 标签(源图 / 生成图)")
    void payloadBytes_recordsByDirection() {
        metrics.recordPayloadBytes("source", 1024L);
        metrics.recordPayloadBytes("source", 2048L);
        metrics.recordPayloadBytes("generated", 4096L);

        DistributionSummary source = registry.find("pixflow.imagegen.payload.bytes")
            .tag("direction", "source").summary();
        DistributionSummary generated = registry.find("pixflow.imagegen.payload.bytes")
            .tag("direction", "generated").summary();
        assertThat(source.count()).isEqualTo(2L);
        assertThat(source.totalAmount()).isEqualTo(3072.0d);
        assertThat(generated.count()).isEqualTo(1L);
        assertThat(generated.totalAmount()).isEqualTo(4096.0d);
    }

    @Test
    @DisplayName("payload.hash.mismatch counter")
    void hashMismatchCounter_increments() {
        metrics.recordPayloadHashMismatch();
        metrics.recordPayloadHashMismatch();
        Counter c = registry.find("pixflow.imagegen.payload.hash.mismatch").counter();
        assertThat(c.count()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("outcomeOf:ImagegenErrorCode → RedrawOutcome 映射与设计一致")
    void outcomeOf_mappingIsCorrect() {
        assertThat(ImagegenMetrics.outcomeOf(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE))
            .isEqualTo(ImagegenMetrics.RedrawOutcome.PAYLOAD_TOO_LARGE);
        assertThat(ImagegenMetrics.outcomeOf(ImagegenErrorCode.IMAGEGEN_CONTENT_POLICY_VIOLATION))
            .isEqualTo(ImagegenMetrics.RedrawOutcome.PROVIDER);
        assertThat(ImagegenMetrics.outcomeOf(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED))
            .isEqualTo(ImagegenMetrics.RedrawOutcome.FAILED);
        assertThat(ImagegenMetrics.outcomeOf(null))
            .isEqualTo(ImagegenMetrics.RedrawOutcome.FAILED);
    }

    @Test
    @DisplayName("负字节值不记录(防被异常抛的 path 注入 0 以下值)")
    void payloadBytes_negativeIsIgnored() {
        metrics.recordPayloadBytes("source", -1L);
        DistributionSummary source = registry.find("pixflow.imagegen.payload.bytes")
            .tag("direction", "source").summary();
        assertThat(source.count()).isEqualTo(0L);
    }
}
