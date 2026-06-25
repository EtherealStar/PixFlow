package com.etherealstar.pixflow.module.dag.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.infra.ai.DagPromptManager;
import com.etherealstar.pixflow.infra.ai.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * DagParser 参数完整即返回预览属性测试（任务 9.5）。
 *
 * <p>Feature: pixflow, Property 19: 对任意解析所得且全部必填参数齐备的 DAG，
 * DagParser 必须返回包含 nodes 与 edges 的 dagPreview，needConfirm=true，
 * 不含任何缺失项，且确认前不生成任务（taskId=null）。
 * Validates: Requirements 6.3
 *
 * <p>LLM 调用经 {@link LlmClient} 接口以内存替身（stub）替代，不依赖真实模型/外部 API。
 */
class DagParserPreviewPropertyTest {

    private static final class StubLlmClient implements LlmClient {
        private String response = "";

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            return response;
        }
    }

    private final StubLlmClient stub = new StubLlmClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final DagParser parser = new DagParser(
            stub, new DagPromptManager(), new DagNormalizer(), mapper);

    /** 生成一个「必填参数齐备」的工具节点 params。 */
    @Provide
    Arbitrary<Map<String, Object>> validNode() {
        Arbitrary<Map<String, Object>> removeBg = Arbitraries.just(node("remove_bg", Map.of()));
        Arbitrary<Map<String, Object>> setBackground = Arbitraries.just(node("set_background", Map.of()));
        Arbitrary<Map<String, Object>> generateCopy = Arbitraries.just(node("generate_copy", Map.of()));
        Arbitrary<Map<String, Object>> resize = Combine.resize();
        Arbitrary<Map<String, Object>> compress = Combine.compress();
        Arbitrary<Map<String, Object>> convertFormat = Combine.convertFormat();
        Arbitrary<Map<String, Object>> watermark = Combine.watermark();
        return Arbitraries.oneOf(removeBg, setBackground, generateCopy,
                resize, compress, convertFormat, watermark);
    }

    /** 节点参数生成工具集合。 */
    private static final class Combine {
        static Arbitrary<Map<String, Object>> resize() {
            return Combinators.combine(
                            Arbitraries.integers().between(1, 4000),
                            Arbitraries.integers().between(1, 4000))
                    .as((w, h) -> node("resize", Map.of("width", w, "height", h)));
        }

        static Arbitrary<Map<String, Object>> compress() {
            return Arbitraries.integers().between(1, 5000)
                    .map(kb -> node("compress", Map.of("max_kb", kb)));
        }

        static Arbitrary<Map<String, Object>> convertFormat() {
            return Arbitraries.of("JPG", "PNG", "WebP")
                    .map(f -> node("convert_format", Map.of("format", f)));
        }

        static Arbitrary<Map<String, Object>> watermark() {
            return Arbitraries.of("center", "top-left", "bottom-right")
                    .map(pos -> node("watermark", Map.of("position", pos, "text", "SALE")));
        }
    }

    @Property(tries = 200)
    void completeParamsYieldPreviewWithoutTask(@ForAll("validNode") Map<String, Object> singleNode,
                                               @ForAll("validNode") Map<String, Object> secondNode) {
        Map<String, Object> n1 = withId(singleNode, "n1");
        Map<String, Object> n2 = withId(secondNode, "n2");
        stub.response = dagJson(List.of(n1, n2), List.of(edge("n1", "n2")));

        DagParseResult result = parser.parse("批量处理这些商品图");

        assertThat(result.hasMissingParams()).isFalse();
        assertThat(result.missingParams()).isEmpty();
        assertThat(result.needConfirm()).isTrue();
        assertThat(result.dagPreview()).isNotNull();
        assertThat(result.dagPreview().getNodes()).hasSize(2);
        assertThat(result.taskId()).isNull();
        assertThat(result.reply()).isNotBlank();
    }

    @Test
    void singleCompleteNodeReturnsPreview() {
        Map<String, Object> n1 = withId(node("resize", Map.of("width", 800, "height", 600)), "n1");
        stub.response = dagJson(List.of(n1), List.of());
        DagParseResult result = parser.parse("缩放到 800x600");
        assertThat(result.hasMissingParams()).isFalse();
        assertThat(result.dagPreview()).isNotNull();
        assertThat(result.dagPreview().getNodes()).hasSize(1);
        assertThat(result.taskId()).isNull();
    }

    // ---- helpers -----------------------------------------------------------

    private static Map<String, Object> node(String tool, Map<String, ?> params) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("tool", tool);
        n.put("params", new LinkedHashMap<>(params));
        return n;
    }

    private static Map<String, Object> withId(Map<String, Object> node, String id) {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("id", id);
        copy.putAll(node);
        return copy;
    }

    private static Map<String, Object> edge(String from, String to) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("from", from);
        e.put("to", to);
        return e;
    }

    private String dagJson(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodes", new ArrayList<>(nodes));
        root.put("edges", new ArrayList<>(edges));
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
