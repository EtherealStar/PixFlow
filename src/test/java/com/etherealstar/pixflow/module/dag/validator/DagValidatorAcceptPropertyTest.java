package com.etherealstar.pixflow.module.dag.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.etherealstar.pixflow.module.dag.DagJsonCodec;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.engine.TopologicalSorter;
import com.etherealstar.pixflow.module.dag.schema.DagValidationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * DagValidator 合法 DAG 通过校验属性测试（任务 10.4）。
 *
 * <p>Feature: pixflow, Property 23: 合法 DAG 通过校验——对节点数在 [1,50]、工具均在白名单、
 * 参数满足 schema、边引用合法且无环的 DAG，校验器必须全部放行（不抛异常）。
 * Validates: Requirements 7.1, 7.8
 *
 * <p>本测试针对纯逻辑 {@link DagValidator}，不涉及 LLM/图片/文案/外部 API。
 */
class DagValidatorAcceptPropertyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DagValidator validator = new DagValidator(
            mapper, new TopologicalSorter(), new DagValidationProperties());
    private final DagJsonCodec codec = new DagJsonCodec(mapper);

    /** 生成一个「合法」工具节点的 (tool, params)。 */
    @Provide
    Arbitrary<Map<String, Object>> validToolSpec() {
        Arbitrary<Map<String, Object>> removeBg = Arbitraries.just(spec("remove_bg", Map.of()));
        Arbitrary<Map<String, Object>> setBg =
                Arbitraries.just(spec("set_background", Map.of("color", "#FFAA00")));
        Arbitrary<Map<String, Object>> copy = Arbitraries.just(spec("generate_copy", Map.of()));
        Arbitrary<Map<String, Object>> resize = Arbitraries.integers().between(1, 4000)
                .map(w -> spec("resize", Map.of("width", w, "height", 600)));
        Arbitrary<Map<String, Object>> compress = Arbitraries.integers().between(1, 5000)
                .map(kb -> spec("compress", Map.of("max_kb", kb)));
        Arbitrary<Map<String, Object>> convert = Arbitraries.of("JPG", "PNG", "WebP")
                .map(f -> spec("convert_format", Map.of("format", f)));
        Arbitrary<Map<String, Object>> watermark = Arbitraries.of("center", "top-left", "bottom-right")
                .map(pos -> spec("watermark", Map.of("position", pos, "text", "SALE")));
        return Arbitraries.oneOf(removeBg, setBg, copy, resize, compress, convert, watermark);
    }

    private static Map<String, Object> spec(String tool, Map<String, ?> params) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("tool", tool);
        m.put("params", params);
        return m;
    }

    @Provide
    Arbitrary<List<Map<String, Object>>> validNodeSpecs() {
        return validToolSpec().list().ofMinSize(1).ofMaxSize(50);
    }

    @Property(tries = 200)
    @SuppressWarnings("unchecked")
    void validLinearDagPassesValidation(@ForAll("validNodeSpecs") List<Map<String, Object>> specs) {
        List<DagNode> nodes = new ArrayList<>();
        List<DagEdge> edges = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Map<String, Object> spec = specs.get(i);
            nodes.add(new DagNode("n" + i, (String) spec.get("tool"),
                    (Map<String, Object>) spec.get("params")));
            if (i > 0) {
                // 线性链：保证无环、边引用均存在
                edges.add(new DagEdge("n" + (i - 1), "n" + i));
            }
        }
        Dag dag = new Dag(nodes, edges);

        // 直接校验已解析 DAG 不抛异常（放行执行）
        assertThatCode(() -> validator.validate(dag)).doesNotThrowAnyException();

        // 经 JSON 往返后仍通过校验，且节点数保持一致
        Dag parsed = validator.validateJson(codec.write(dag));
        assertThat(parsed.getNodes()).hasSameSizeAs(nodes);
    }

    @Test
    void singleNodeBoundaryPasses() {
        Dag dag = new Dag(List.of(new DagNode("n1", "remove_bg", Map.of())), new ArrayList<>());
        assertThatCode(() -> validator.validate(dag)).doesNotThrowAnyException();
    }

    @Test
    void fiftyNodeBoundaryPasses() {
        List<DagNode> nodes = new ArrayList<>();
        List<DagEdge> edges = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            nodes.add(new DagNode("n" + i, "resize", Map.of("width", 800, "height", 600)));
            if (i > 0) {
                edges.add(new DagEdge("n" + (i - 1), "n" + i));
            }
        }
        assertThatCode(() -> validator.validate(new Dag(nodes, edges))).doesNotThrowAnyException();
    }

    @Test
    void diamondShapedAcyclicDagPasses() {
        // n0 -> n1, n0 -> n2, n1 -> n3, n2 -> n3
        List<DagNode> nodes = List.of(
                new DagNode("n0", "remove_bg", Map.of()),
                new DagNode("n1", "resize", Map.of("width", 800, "height", 600)),
                new DagNode("n2", "set_background", Map.of("color", "#FFFFFF")),
                new DagNode("n3", "convert_format", Map.of("format", "PNG")));
        List<DagEdge> edges = List.of(
                new DagEdge("n0", "n1"), new DagEdge("n0", "n2"),
                new DagEdge("n1", "n3"), new DagEdge("n2", "n3"));
        assertThatCode(() -> validator.validate(new Dag(nodes, edges))).doesNotThrowAnyException();
    }
}
