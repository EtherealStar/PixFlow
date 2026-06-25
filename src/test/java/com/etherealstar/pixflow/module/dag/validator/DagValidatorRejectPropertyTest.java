package com.etherealstar.pixflow.module.dag.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
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
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * DagValidator 拒绝非法结构属性测试（任务 10.3）。
 *
 * <p>Feature: pixflow, Property 22: DAG 校验拒绝非法结构——对结构缺失、节点数越界、含非白名单工具、
 * 参数不满足 schema、边引用不存在节点、或含环的 DAG，校验器必须以对应错误码拒绝且不放行执行。
 * Validates: Requirements 7.2, 7.3, 7.4, 7.5, 7.6, 7.7
 *
 * <p>本测试针对纯逻辑 {@link DagValidator}，不涉及 LLM/图片/文案/外部 API。
 */
class DagValidatorRejectPropertyTest {

    private final DagValidator validator = new DagValidator(
            new ObjectMapper(), new TopologicalSorter(), new DagValidationProperties());

    private static DagNode resize(String id) {
        return new DagNode(id, "resize", Map.of("width", 800, "height", 600));
    }

    private static void assertRejectedWith(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    // ---- 7.6 节点数越界 → DAG_NODE_COUNT_INVALID --------------------------

    @Property(tries = 200)
    void tooManyNodesRejected(@ForAll @IntRange(min = 51, max = 200) int count) {
        List<DagNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            nodes.add(resize("n" + i));
        }
        assertRejectedWith(() -> validator.validate(new Dag(nodes, new ArrayList<>())),
                ErrorCode.DAG_NODE_COUNT_INVALID);
    }

    @Test
    void zeroNodesRejected() {
        assertRejectedWith(() -> validator.validate(new Dag(new ArrayList<>(), new ArrayList<>())),
                ErrorCode.DAG_NODE_COUNT_INVALID);
    }

    // ---- 7.4 非白名单工具 → DAG_INVALID_TOOL ------------------------------

    @Provide
    Arbitrary<String> nonWhitelistTools() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !List.of("remove_bg", "set_background", "resize", "compress",
                        "watermark", "convert_format", "generate_copy").contains(s));
    }

    @Property(tries = 200)
    void nonWhitelistToolRejected(@ForAll("nonWhitelistTools") String tool) {
        DagNode node = new DagNode("n1", tool, Map.of());
        assertRejectedWith(() -> validator.validate(new Dag(List.of(node), new ArrayList<>())),
                ErrorCode.DAG_INVALID_TOOL);
    }

    // ---- 7.5 参数不满足 schema → DAG_PARAM_INVALID ------------------------

    @Property(tries = 200)
    void resizeNonPositiveDimensionRejected(@ForAll @IntRange(min = -100, max = 0) int badValue) {
        DagNode node = new DagNode("n1", "resize", Map.of("width", badValue, "height", 600));
        assertRejectedWith(() -> validator.validate(new Dag(List.of(node), new ArrayList<>())),
                ErrorCode.DAG_PARAM_INVALID);
    }

    @Test
    void missingRequiredParamRejected() {
        DagNode node = new DagNode("n1", "compress", Map.of()); // 缺 max_kb
        assertRejectedWith(() -> validator.validate(new Dag(List.of(node), new ArrayList<>())),
                ErrorCode.DAG_PARAM_INVALID);
    }

    @Test
    void paramErrorIncludesNodeId() {
        DagNode node = new DagNode("badNode", "resize", Map.of("width", 0, "height", 600));
        assertThatThrownBy(() -> validator.validate(new Dag(List.of(node), new ArrayList<>())))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DAG_PARAM_INVALID);
                    assertThat(ex.getDetails()).containsEntry("nodeId", "badNode");
                });
    }

    // ---- 7.7 边引用不存在节点 → DAG_EDGE_INVALID --------------------------

    @Test
    void edgeReferencingMissingNodeRejected() {
        Dag dag = new Dag(List.of(resize("n1")), List.of(new DagEdge("n1", "ghost")));
        assertRejectedWith(() -> validator.validate(dag), ErrorCode.DAG_EDGE_INVALID);

        Dag dag2 = new Dag(List.of(resize("n1")), List.of(new DagEdge("ghost", "n1")));
        assertRejectedWith(() -> validator.validate(dag2), ErrorCode.DAG_EDGE_INVALID);
    }

    // ---- 7.3 含环 → DAG_CYCLE_DETECTED ------------------------------------

    @Test
    void cyclicDagRejected() {
        Dag dag = new Dag(
                List.of(resize("n1"), resize("n2")),
                List.of(new DagEdge("n1", "n2"), new DagEdge("n2", "n1")));
        assertRejectedWith(() -> validator.validate(dag), ErrorCode.DAG_CYCLE_DETECTED);
    }

    @Test
    void selfLoopRejected() {
        Dag dag = new Dag(List.of(resize("n1")), List.of(new DagEdge("n1", "n1")));
        assertRejectedWith(() -> validator.validate(dag), ErrorCode.DAG_CYCLE_DETECTED);
    }

    // ---- 7.2 结构非法（JSON 入口）→ DAG_STRUCTURE_INVALID -----------------

    @Provide
    Arbitrary<String> structurallyInvalidJson() {
        return Arbitraries.of(
                "",
                "   ",
                "{ broken",
                "[]",
                "123",
                "{}",
                "{\"nodes\": []}",
                "{\"edges\": []}",
                "{\"nodes\": {}, \"edges\": {}}",
                "{\"nodes\": [{\"tool\": \"resize\"}], \"edges\": []}",
                "{\"nodes\": [{\"id\": \"n1\", \"params\": []}], \"edges\": []}",
                "{\"nodes\": [{\"id\": \"n1\", \"tool\": \"resize\"}], \"edges\": [{\"from\": \"n1\"}]}");
    }

    @Property
    void structurallyInvalidJsonRejected(@ForAll("structurallyInvalidJson") String json) {
        assertRejectedWith(() -> validator.validateJson(json), ErrorCode.DAG_STRUCTURE_INVALID);
    }
}
