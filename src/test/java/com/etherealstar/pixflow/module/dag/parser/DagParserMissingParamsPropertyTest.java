package com.etherealstar.pixflow.module.dag.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.infra.ai.DagPromptManager;
import com.etherealstar.pixflow.infra.ai.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * DagParser 缺失必填参数追问属性测试（任务 9.4）。
 *
 * <p>Feature: pixflow, Property 18: 对任意解析所得且存在「工具节点缺少必填参数」的 DAG，
 * DagParser 必须：(a) 逐项列出全部缺失的必填参数（nodeId + param），(b) needConfirm=true，
 * (c) 不返回 dagPreview（不自动填充任何缺失的必填参数），(d) 不生成任务（taskId=null）。
 * Validates: Requirements 6.2, 6.5
 *
 * <p>LLM 调用经 {@link LlmClient} 接口以内存替身（stub）替代，不依赖真实模型/外部 API。
 */
class DagParserMissingParamsPropertyTest {

    /** 可设置返回值的内存 LLM 替身。 */
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

    /** 单节点缺参场景：工具名、提供的参数、期望缺失的参数标记集合。 */
    private record Scenario(String tool, Map<String, Object> params, Set<String> expectedMissing) {
    }

    @Provide
    Arbitrary<Scenario> missingScenarios() {
        return Arbitraries.oneOf(resizeScenarios(), compressScenarios(),
                convertFormatScenarios(), watermarkScenarios());
    }

    private Arbitrary<Scenario> resizeScenarios() {
        Arbitrary<Boolean> incW = Arbitraries.of(true, false);
        Arbitrary<Boolean> incH = Arbitraries.of(true, false);
        return Combinators.combine(incW, incH).as((w, h) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            Set<String> missing = new LinkedHashSet<>();
            if (w) {
                p.put("width", 800);
            } else {
                missing.add("width");
            }
            if (h) {
                p.put("height", 600);
            } else {
                missing.add("height");
            }
            return new Scenario("resize", p, missing);
        });
    }

    private Arbitrary<Scenario> compressScenarios() {
        return Arbitraries.of(true, false).map(inc -> {
            Map<String, Object> p = new LinkedHashMap<>();
            Set<String> missing = new LinkedHashSet<>();
            if (inc) {
                p.put("max_kb", 200);
            } else {
                missing.add("max_kb");
            }
            return new Scenario("compress", p, missing);
        });
    }

    private Arbitrary<Scenario> convertFormatScenarios() {
        return Arbitraries.of(true, false).map(inc -> {
            Map<String, Object> p = new LinkedHashMap<>();
            Set<String> missing = new LinkedHashSet<>();
            if (inc) {
                p.put("format", "PNG");
            } else {
                missing.add("format");
            }
            return new Scenario("convert_format", p, missing);
        });
    }

    private Arbitrary<Scenario> watermarkScenarios() {
        Arbitrary<Boolean> incPos = Arbitraries.of(true, false);
        // 0 = 都不提供, 1 = 提供 text, 2 = 提供 image
        Arbitrary<Integer> oneOfChoice = Arbitraries.integers().between(0, 2);
        return Combinators.combine(incPos, oneOfChoice).as((pos, choice) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            Set<String> missing = new LinkedHashSet<>();
            if (pos) {
                p.put("position", "center");
            } else {
                missing.add("position");
            }
            if (choice == 1) {
                p.put("text", "SALE");
            } else if (choice == 2) {
                p.put("image", "logo.png");
            } else {
                // 二选一组：text 与 image 均缺失 → 以 "text|image" 标记
                missing.add("text|image");
            }
            return new Scenario("watermark", p, missing);
        });
    }

    @Property(tries = 300)
    void missingRequiredParamsAreAskedAndNotFilled(@ForAll("missingScenarios") Scenario scenario) {
        // 仅针对「确实存在缺失」的场景断言追问行为
        Assume.that(!scenario.expectedMissing().isEmpty());

        stub.response = singleNodeDagJson("n1", scenario.tool(), scenario.params());
        DagParseResult result = parser.parse("请处理这些图片");

        // (b) 需要确认
        assertThat(result.needConfirm()).isTrue();
        // (c) 不返回预览（不自动填充缺失必填参数）
        assertThat(result.dagPreview()).isNull();
        // (d) 不生成任务
        assertThat(result.taskId()).isNull();
        // (a) 逐项列出全部缺失项
        assertThat(result.hasMissingParams()).isTrue();

        Set<String> actualTokens = new LinkedHashSet<>();
        for (MissingParam mp : result.missingParams()) {
            assertThat(mp.nodeId()).isEqualTo("n1");
            actualTokens.add(mp.param());
        }
        assertThat(actualTokens).isEqualTo(scenario.expectedMissing());
    }

    @Test
    void emptyParamsReportEveryRequiredParam() {
        stub.response = singleNodeDagJson("n1", "resize", Map.of());
        DagParseResult result = parser.parse("缩放");
        assertThat(result.hasMissingParams()).isTrue();
        assertThat(result.dagPreview()).isNull();
        assertThat(result.taskId()).isNull();
        Set<String> tokens = new LinkedHashSet<>();
        result.missingParams().forEach(mp -> tokens.add(mp.param()));
        assertThat(tokens).containsExactlyInAnyOrder("width", "height");
    }

    @Test
    void watermarkOneOfGroupReportedWhenBothAbsent() {
        stub.response = singleNodeDagJson("n1", "watermark", Map.of("position", "center"));
        DagParseResult result = parser.parse("加水印");
        assertThat(result.hasMissingParams()).isTrue();
        assertThat(result.missingParams()).hasSize(1);
        assertThat(result.missingParams().get(0).param()).isEqualTo("text|image");
    }

    private String singleNodeDagJson(String id, String tool, Map<String, Object> params) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("tool", tool);
        node.put("params", params);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodes", List.of(node));
        root.put("edges", new ArrayList<>());
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
