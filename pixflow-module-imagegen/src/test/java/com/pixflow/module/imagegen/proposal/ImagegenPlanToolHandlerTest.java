package com.pixflow.module.imagegen.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.metrics.ImagegenMetrics;
import com.pixflow.contracts.proposal.PendingPlanPort;
import com.pixflow.contracts.proposal.PendingPlanProposal;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImagegenPlanToolHandler 单测(对齐 imagegen.md §五 / §七 / §十五)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常入参 → 入队 → 返回 {planId, payloadHash, sourceCount, summary}</li>
 *   <li>浅层非法(source_image_ids 空 / prompt 空) → 工具 error 不入队</li>
 *   <li>深校验失败(白名单外键) → 工具 error 不入队</li>
 *   <li>schema 不含 token 字段 + handler 标 readOnly</li>
 * </ul>
 */
class ImagegenPlanToolHandlerTest {

    private ImagegenPlanService service;
    private ImagegenPlanToolHandler handler;
    private FakePendingPlanPort port;
    private FakeSourceImageReader reader;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ImagegenProperties properties = new ImagegenProperties();
        port = new FakePendingPlanPort();
        reader = new FakeSourceImageReader();
        ImagegenPlanValidator validator = new ImagegenPlanValidator(properties, reader);
        ImagegenPayloadHasher hasher = new ImagegenPayloadHasher();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ImagegenMetrics metrics = new ImagegenMetrics(registry);
        service = new ImagegenPlanService(validator, port, hasher, objectMapper,
            java.time.Clock.systemUTC(), metrics);
        handler = new ImagegenPlanToolHandler(service, objectMapper);
    }

    private ToolInvocation invocation(String toolCallId, Map<String, Object> args, String packageId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("packageId", packageId);
        return new ToolInvocation(
            toolCallId,
            ImagegenPlanToolHandler.TOOL_NAME,
            args,
            "conv-1",
            1,
            "trace-1",
            RuntimeScope.main(),
            meta);
    }

    @Test
    @DisplayName("正常入参:入队 + 返回 planId/payloadHash/sourceCount/summary")
    void handle_happyPath() throws Exception {
        reader.byId.put("img-1", info("img-1"));
        reader.byId.put("img-2", info("img-2"));
        Map<String, Object> args = Map.of(
            "source_image_ids", List.of("img-2", "img-1"),
            "prompt", "用 A 风格重绘",
            "note", "用户偏好测试",
            "params", Map.of("style", "A"));

        ToolHandlerOutput out = handler.handle(invocation("tc-1", args, "pkg-1"));
        String json = out.content();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(json, Map.class);

        assertThat(result).containsKey("planId");
        assertThat(result.get("sourceCount")).isEqualTo(2);
        assertThat(result.get("payloadHash")).isNotNull();
        assertThat(String.valueOf(result.get("summary"))).contains("2 张");

        // 入队成功
        assertThat(port.enqueued).hasSize(1);
        assertThat(port.enqueued.get(0).payloadJson()).contains("\"sourceImageIds\":[\"img-1\",\"img-2\"]");
    }

    @Test
    @DisplayName("浅层非法:source_image_ids 空 → IMAGEGEN_SOURCE_IMAGE_NOT_FOUND 不入队")
    void handle_emptySourceIds_returnsError() throws Exception {
        Map<String, Object> args = Map.of(
            "source_image_ids", List.of(),
            "prompt", "用 A 风格重绘");

        ToolHandlerOutput out = handler.handle(invocation("tc-2", args, "pkg-1"));
        Map<?, ?> result = objectMapper.readValue(out.content(), Map.class);
        assertThat(result.get("error").toString()).startsWith("IMAGEGEN_SOURCE_IMAGE_NOT_FOUND:");
        assertThat(port.enqueued).isEmpty();
    }

    @Test
    @DisplayName("浅层非法:prompt 空 → IMAGEGEN_PROMPT_INVALID 不入队")
    void handle_blankPrompt_returnsError() throws Exception {
        Map<String, Object> args = Map.of(
            "source_image_ids", List.of("img-1"),
            "prompt", " ");

        ToolHandlerOutput out = handler.handle(invocation("tc-3", args, "pkg-1"));
        Map<?, ?> result = objectMapper.readValue(out.content(), Map.class);
        assertThat(result.get("error").toString()).startsWith("IMAGEGEN_PROMPT_INVALID:");
        assertThat(port.enqueued).isEmpty();
    }

    @Test
    @DisplayName("缺少 packageId → IMAGEGEN_SOURCE_NOT_IN_PACKAGE")
    void handle_missingPackageId_returnsError() throws Exception {
        Map<String, Object> args = Map.of(
            "source_image_ids", List.of("img-1"),
            "prompt", "x");

        ToolHandlerOutput out = handler.handle(invocation("tc-4", args, null));
        Map<?, ?> result = objectMapper.readValue(out.content(), Map.class);
        assertThat(result.get("error").toString()).startsWith("IMAGEGEN_SOURCE_NOT_IN_PACKAGE:");
        assertThat(port.enqueued).isEmpty();
    }

    @Test
    @DisplayName("深校验:白名单外键 → IMAGEGEN_PROMPT_INVALID 不入队")
    void handle_unknownParamKey_returnsError() throws Exception {
        reader.byId.put("img-1", info("img-1"));
        Map<String, Object> badParams = new HashMap<>();
        badParams.put("style", "A");
        badParams.put("secrets", "ak-xxx");
        Map<String, Object> args = Map.of(
            "source_image_ids", List.of("img-1"),
            "prompt", "x",
            "params", badParams);

        ToolHandlerOutput out = handler.handle(invocation("tc-5", args, "pkg-1"));
        Map<?, ?> result = objectMapper.readValue(out.content(), Map.class);
        assertThat(result.get("error").toString()).startsWith("IMAGEGEN_PROMPT_INVALID:");
        assertThat(port.enqueued).isEmpty();
    }

    @Test
    @DisplayName("descriptor:readOnlyHint=true + inputSchema 不含 token 字段")
    void descriptor_readOnlyAndZeroTokenFields() {
        // 调一次 setUp 让 handler 注入 services,然后触发 @Bean 方法
        ToolDescriptor desc = handler.submitImagegenPlanDescriptor();
        assertThat(desc.readOnlyHint()).isTrue();
        assertThat(desc.name()).isEqualTo(ImagegenPlanToolHandler.TOOL_NAME);

        Map<String, Object> input = desc.inputSchema();
        // schema 顶层只允许 source_image_ids / prompt / note / params
        assertThat(input).containsKeys("type", "properties");
        Map<?, ?> props = (Map<?, ?>) input.get("properties");
        @SuppressWarnings("unchecked")
        List<String> propKeys = (List<String>) (List<?>) java.util.List.copyOf(props.keySet());
        assertThat(propKeys).containsExactlyInAnyOrder(
            "source_image_ids", "prompt", "note", "params");
        // 不含任何 token 字段
        for (Object key : props.keySet()) {
            String k = String.valueOf(key).toLowerCase();
            assertThat(k).doesNotContain("token");
        }
        // 顶层 required 包含 source_image_ids + prompt
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) input.get("required");
        assertThat(required).containsExactlyInAnyOrder(
            "source_image_ids", "prompt");
    }

    private static SourceImageInfo info(String imageId) {
        return new SourceImageInfo(imageId, "pkg-1", "sku-" + imageId,
            "packages/pkg-1/" + imageId, "image/png", null, null);
    }

    static class FakePendingPlanPort implements PendingPlanPort {
        final List<PendingPlanProposal> enqueued = new java.util.ArrayList<>();
        @Override
        public synchronized String enqueue(PendingPlanProposal proposal) {
            enqueued.add(proposal);
            return "plan-" + (enqueued.size() + 1);
        }
        @Override
        public java.util.Optional<PendingPlanProposal> find(String planId) {
            return enqueued.stream().filter(p -> p.toolCallId().equals(planId)).findFirst();
        }
    }

    static class FakeSourceImageReader implements SourceImageReader {
        final Map<String, SourceImageInfo> byId = new HashMap<>();
        @Override
        public List<SourceImageInfo> findAll(List<String> imageIds, String packageId) {
            return imageIds.stream().filter(byId::containsKey).map(byId::get).toList();
        }
    }

    /** 防御性:枚举 → 字符串值校验。 */
    @Test
    @DisplayName("ImagegenErrorCode 枚举对齐(确保 handler 错误字符串拼写正确)")
    void enumCodesAlignWithHandlerStrings() {
        // handler 拼的字符串应等于对应 enum 的 code()
        assertThat(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID.code()).isEqualTo("IMAGEGEN_PROMPT_INVALID");
        assertThat(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND.code()).isEqualTo("IMAGEGEN_SOURCE_IMAGE_NOT_FOUND");
        assertThat(ImagegenErrorCode.IMAGEGEN_SOURCE_NOT_IN_PACKAGE.code()).isEqualTo("IMAGEGEN_SOURCE_NOT_IN_PACKAGE");
    }
}
