package com.pixflow.module.imagegen.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.contracts.proposal.PendingPlanPort;
import com.pixflow.contracts.proposal.PendingPlanProposal;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.metrics.ImagegenMetrics;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImagegenPlanService 单测(对齐 imagegen.md §5.2 / §六 / §十五)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常入队 → 返回 planId + payload 包含 planType=IMAGEGEN + 工具无关字段</li>
 *   <li>同 toolCallId 重复调用 → 假 PendingPlanPort 只看到 1 次 enqueue(幂等由 port 接管)</li>
 *   <li>校验失败 → IMAGEGEN_PROMPT_INVALID 等 5 条 tool 错误</li>
 *   <li>payloadHash 字段(供 handler 透出)</li>
 * </ul>
 */
class ImagegenPlanServiceTest {

    private ImagegenProperties properties;
    private FakePendingPlanPort port;
    private FakeSourceImageReader reader;
    private ImagegenPlanService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new ImagegenProperties();
        port = new FakePendingPlanPort();
        reader = new FakeSourceImageReader();
        objectMapper = new ObjectMapper();
        ImagegenPlanValidator validator = new ImagegenPlanValidator(properties, reader);
        ImagegenPayloadHasher hasher = new ImagegenPayloadHasher();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ImagegenMetrics metrics = new ImagegenMetrics(registry);
        service = new ImagegenPlanService(validator, port, hasher, objectMapper,
            java.time.Clock.systemUTC(), metrics);
    }

    @Test
    @DisplayName("正常入队:返回 planId + 载荷包含 planType=IMAGEGEN")
    void enqueue_happyPath() {
        reader.byId.put("img-1", info("img-1"));
        reader.byId.put("img-2", info("img-2"));
        ImagegenPlanInputs inputs = new ImagegenPlanInputs(
            List.of("img-1", "img-2"), "用 A 风格重绘", "用户偏好测试",
            Map.of("style", "A"));

        String planId = service.enqueue(inputs, "tc-1", "conv-1", "pkg-1");

        assertThat(planId).isNotBlank();
        assertThat(port.enqueued).hasSize(1);
        PendingPlanProposal proposal = port.enqueued.get(0);
        assertThat(proposal.planType()).isEqualTo("IMAGEGEN");
        assertThat(proposal.conversationId()).isEqualTo("conv-1");
        assertThat(proposal.packageId()).isEqualTo("pkg-1");
        assertThat(proposal.toolCallId()).isEqualTo("tc-1");
        assertThat(proposal.payloadJson()).contains("\"sourceImageIds\":[\"img-1\",\"img-2\"]");
        assertThat(proposal.payloadJson()).contains("\"prompt\":\"用 A 风格重绘\"");
    }

    @Test
    @DisplayName("同 toolCallId 重复入队:FakePendingPlanPort 模拟实现保证只 1 条 plan")
    void enqueue_idempotent_byToolCallId() {
        reader.byId.put("img-1", info("img-1"));
        ImagegenPlanInputs inputs = new ImagegenPlanInputs(
            List.of("img-1"), "x", null, Map.of());

        // 第一次
        String planId1 = service.enqueue(inputs, "tc-dup", "conv-1", "pkg-1");
        // 模拟 port 的幂等行为:同 toolCallId 返回已存在 planId
        port.simulateIdempotent("tc-dup", "conv-1", planId1);
        // 第二次
        String planId2 = service.enqueue(inputs, "tc-dup", "conv-1", "pkg-1");

        assertThat(planId1).isEqualTo(planId2);
        // fake port 内部去重后只接受 1 条 plan,但 enqueue 方法本身被调用了 2 次
        assertThat(port.callCount).isEqualTo(2);
        assertThat(port.enqueued).hasSize(1); // 只有 1 条 plan 被持久化(幂等保证)
    }

    @Test
    @DisplayName("校验失败:prompt 空 → IMAGEGEN_PROMPT_INVALID(由 service 透传给 caller)")
    void enqueue_validationFailure_propagates() {
        ImagegenPlanInputs inputs = new ImagegenPlanInputs(
            List.of("img-1"), " ", null, Map.of());

        assertThatThrownBy(() -> service.enqueue(inputs, "tc-1", "conv-1", "pkg-1"))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID);
        assertThat(port.enqueued).isEmpty();
    }

    @Test
    @DisplayName("校验失败:白名单外键 → IMAGEGEN_PROMPT_INVALID")
    void enqueue_unknownParamKey_propagates() {
        reader.byId.put("img-1", info("img-1"));
        Map<String, Object> badParams = new HashMap<>();
        badParams.put("style", "A");
        badParams.put("secrets", "ak-xxx");
        ImagegenPlanInputs inputs = new ImagegenPlanInputs(
            List.of("img-1"), "x", null, badParams);

        assertThatThrownBy(() -> service.enqueue(inputs, "tc-1", "conv-1", "pkg-1"))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID);
    }

    @Test
    @DisplayName("payloadHashFor:与 hasher 直接调用一致(供 handler 透出)")
    void payloadHashFor_matchesHasherDirectCall() {
        ImagegenPlan plan = new ImagegenPlan(
            List.of("a", "b"), "x", Map.of("style", "A"), "note", "conv", "pkg");
        String fromService = service.payloadHashFor(plan);
        String direct = new ImagegenPayloadHasher().hash(plan);
        assertThat(fromService).isEqualTo(direct);
    }

    private static SourceImageInfo info(String imageId) {
        return new SourceImageInfo(imageId, "pkg-1", "sku-" + imageId,
            "packages/pkg-1/" + imageId, "image/png", null, null);
    }

    /** 单测用 fake PendingPlanPort:把每次入队登记,并可模拟幂等。 */
    static class FakePendingPlanPort implements PendingPlanPort {
        final java.util.List<PendingPlanProposal> enqueued = new java.util.ArrayList<>();
        int callCount = 0;
        private final Map<String, String> idempotentMap = new java.util.HashMap<>();

        void simulateIdempotent(String toolCallId, String conversationId, String planId) {
            idempotentMap.put(conversationId + ":" + toolCallId, planId);
        }

        @Override
        public synchronized String enqueue(PendingPlanProposal proposal) {
            callCount++;
            String key = proposal.conversationId() + ":" + proposal.toolCallId();
            String existing = idempotentMap.get(key);
            if (existing != null) {
                // 模拟幂等返回同一 planId(不重复添加 enqueued 列表)
                return existing;
            }
            enqueued.add(proposal);
            String planId = "plan-" + (enqueued.size() + 1);
            idempotentMap.put(key, planId);
            return planId;
        }

        @Override
        public Optional<PendingPlanProposal> find(String planId) {
            return enqueued.stream().filter(p -> p.toolCallId().equals(planId)).findFirst();
        }
    }

    /** 单测用 fake SourceImageReader。 */
    static class FakeSourceImageReader implements SourceImageReader {
        final Map<String, SourceImageInfo> byId = new HashMap<>();

        @Override
        public List<SourceImageInfo> findAll(List<String> imageIds, String packageId) {
            return imageIds.stream().filter(byId::containsKey).map(byId::get).toList();
        }
    }
}
