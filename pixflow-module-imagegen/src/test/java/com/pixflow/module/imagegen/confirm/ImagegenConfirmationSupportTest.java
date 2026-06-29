package com.pixflow.module.imagegen.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.metrics.ImagegenMetrics;
import com.pixflow.module.imagegen.port.PendingPlanPort;
import com.pixflow.module.imagegen.port.PendingPlanProposal;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImagegenConfirmationSupport 单测(对齐 imagegen.md §七 / §十五)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常:重算 payloadHash 与 hasher 直接调用结果一致</li>
 *   <li>expectedCount = 源图张数(与「1 源图 → 1 重绘」对齐)</li>
 *   <li>payloadHash 不一致 → IMAGEGEN_PAYLOAD_HASH_MISMATCH + 指标递增</li>
 *   <li>planId 找不到 → IMAGEGEN_PLAN_NOT_FOUND</li>
 *   <li>payloadJson 损坏 → IMAGEGEN_PLAN_NOT_FOUND</li>
 * </ul>
 */
class ImagegenConfirmationSupportTest {

    private FakePendingPlanPort port;
    private ObjectMapper objectMapper;
    private ImagegenPayloadHasher hasher;
    private ImagegenMetrics metrics;
    private SimpleMeterRegistry registry;
    private ImagegenConfirmationSupport support;

    @BeforeEach
    void setUp() {
        port = new FakePendingPlanPort();
        objectMapper = new ObjectMapper();
        hasher = new ImagegenPayloadHasher();
        registry = new SimpleMeterRegistry();
        metrics = new ImagegenMetrics(registry);
        support = new ImagegenConfirmationSupport(port, hasher, objectMapper, metrics);
    }

    @Test
    @DisplayName("payloadHash:与 hasher 直接调用结果一致")
    void payloadHash_matchesHasherDirectCall() throws Exception {
        ImagegenPlan plan = new ImagegenPlan(
            List.of("img-1", "img-2"),
            "用 A 风格重绘",
            Map.of("style", "A"),
            "note",
            "conv-1", "pkg-1");
        String json = objectMapper.writeValueAsString(plan);
        port.put("plan-1", new PendingPlanProposal(
            "IMAGEGEN", json, "conv-1", "pkg-1", "tc-1", Instant.now()));

        String hashFromSupport = support.payloadHash("plan-1");
        String hashFromHasher = hasher.hash(plan);
        assertThat(hashFromSupport).isEqualTo(hashFromHasher);
    }

    @Test
    @DisplayName("expectedCount:与源图张数一致(1 源图 → 1 重绘)")
    void expectedCount_matchesSourceImageCount() throws Exception {
        ImagegenPlan plan = new ImagegenPlan(
            List.of("a", "b", "c"),
            "x", Map.of(), null, "conv", "pkg");
        port.put("plan-2", new PendingPlanProposal(
            "IMAGEGEN", objectMapper.writeValueAsString(plan), "conv", "pkg", "tc-2", Instant.now()));

        assertThat(support.expectedCount("plan-2")).isEqualTo(3);
    }

    @Test
    @DisplayName("verifyHash:一致时无异常")
    void verifyHash_consistent_doesNothing() throws Exception {
        ImagegenPlan plan = new ImagegenPlan(
            List.of("a"), "x", Map.of("style", "A"), null, "c", "p");
        port.put("plan-3", new PendingPlanProposal(
            "IMAGEGEN", objectMapper.writeValueAsString(plan), "c", "p", "tc-3", Instant.now()));

        String hash = support.payloadHash("plan-3");
        support.verifyHash("plan-3", hash);
    }

    @Test
    @DisplayName("verifyHash:不一致 → IMAGEGEN_PAYLOAD_HASH_MISMATCH + 指标递增")
    void verifyHash_inconsistent_throws_andIncrementsMetric() throws Exception {
        ImagegenPlan plan = new ImagegenPlan(
            List.of("a"), "x", Map.of(), null, "c", "p");
        port.put("plan-4", new PendingPlanProposal(
            "IMAGEGEN", objectMapper.writeValueAsString(plan), "c", "p", "tc-4", Instant.now()));

        long before = counterValue("pixflow.imagegen.payload.hash.mismatch");

        assertThatThrownBy(() -> support.verifyHash("plan-4", "deadbeef".repeat(8)))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PAYLOAD_HASH_MISMATCH);

        long after = counterValue("pixflow.imagegen.payload.hash.mismatch");
        assertThat(after).isEqualTo(before + 1L);
    }

    @Test
    @DisplayName("planId 找不到 → IMAGEGEN_PLAN_NOT_FOUND")
    void payloadHash_planNotFound_throws() {
        assertThatThrownBy(() -> support.payloadHash("missing-plan"))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PLAN_NOT_FOUND);
    }

    @Test
    @DisplayName("payloadJson 损坏 → IMAGEGEN_PLAN_NOT_FOUND(归类为不可读提案)")
    void payloadHash_corruptJson_throws() {
        port.put("plan-bad", new PendingPlanProposal(
            "IMAGEGEN", "{not-valid-json", "c", "p", "tc-bad", Instant.now()));

        assertThatThrownBy(() -> support.payloadHash("plan-bad"))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PLAN_NOT_FOUND);
    }

    private long counterValue(String name) {
        Counter c = registry.find(name).counter();
        return c == null ? 0L : (long) c.count();
    }

    static class FakePendingPlanPort implements PendingPlanPort {
        private final java.util.HashMap<String, PendingPlanProposal> map = new java.util.HashMap<>();

        void put(String planId, PendingPlanProposal proposal) {
            map.put(planId, proposal);
        }

        @Override
        public String enqueue(PendingPlanProposal proposal) {
            map.put("plan-auto", proposal);
            return "plan-auto";
        }

        @Override
        public Optional<PendingPlanProposal> find(String planId) {
            return Optional.ofNullable(map.get(planId));
        }
    }
}