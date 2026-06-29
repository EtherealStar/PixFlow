package com.pixflow.module.dag.validate;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagEdge;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.validate.rule.AcyclicRule;
import com.pixflow.module.dag.validate.rule.EdgeRule;
import com.pixflow.module.dag.validate.rule.GroupBranchRule;
import com.pixflow.module.dag.validate.rule.NodeLimitRule;
import com.pixflow.module.dag.validate.rule.OpOrderRule;
import com.pixflow.module.dag.validate.rule.ParamsRule;
import com.pixflow.module.dag.validate.rule.StructureRule;
import com.pixflow.module.dag.validate.rule.WhitelistRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DagValidator 全规则测试:覆盖每个规则的失败/成功路径。
 */
class DagValidatorTest {

    private DagValidator validator;

    @BeforeEach
    void setUp() {
        ParamSchemaRegistry registry = new ParamSchemaRegistry();
        validator = new DagValidator(registry, 50, 1);
    }

    @Test
    void ruleOrder_isStable() {
        assertThat(validator.ruleOrder()).containsExactly(
            "STRUCTURE", "NODE_LIMIT", "WHITELIST", "PARAMS",
            "EDGE", "ACYCLIC", "GROUP_BRANCH", "OP_ORDER");
    }

    @Test
    void validate_simpleLinearDag_succeeds() {
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.REMOVE_BG, Map.of()),
                new DagNode("n2", PixelTool.SET_BACKGROUND, Map.of("color", "#FFFFFF")),
                new DagNode("n3", PixelTool.RESIZE, Map.of("width", 800, "height", 800))
            ),
            List.of(
                new DagEdge("n1", "n2"),
                new DagEdge("n2", "n3")
            )
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void validate_duplicateNodeId_fails() {
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.REMOVE_BG, Map.of()),
                new DagNode("n1", PixelTool.RESIZE, Map.of("width", 800))
            ),
            List.of()
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_STRUCTURE") && e.contains("重复"));
    }

    @Test
    void validate_emptyNodes_fails() {
        DagDocument doc = new DagDocument(List.of(), List.of());
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_STRUCTURE") && e.contains("至少"));
    }

    @Test
    void validate_tooManyNodes_fails() {
        java.util.List<DagNode> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            nodes.add(new DagNode("n" + i, PixelTool.RESIZE, Map.of("width", 100)));
        }
        DagDocument doc = new DagDocument(nodes, List.of());
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_NODE_LIMIT_EXCEEDED"));
    }

    @Test
    void validate_danglingEdge_fails() {
        DagDocument doc = new DagDocument(
            List.of(new DagNode("n1", PixelTool.RESIZE, Map.of("width", 100))),
            List.of(new DagEdge("n1", "missing"))
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_STRUCTURE") && e.contains("边引用"));
    }

    @Test
    void validate_cycle_fails() {
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.REMOVE_BG, Map.of()),
                new DagNode("n2", PixelTool.RESIZE, Map.of("width", 100))
            ),
            List.of(
                new DagEdge("n1", "n2"),
                new DagEdge("n2", "n1")
            )
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_HAS_CYCLE"));
    }

    @Test
    void validate_removeBg_notFirst_fails() {
        // source=resize, 然后是 set_background, 然后才是 remove_bg
        // 链首约束要求 remove_bg 必须是 path[1](source 之后的第一个),若 path[1] != remove_bg 违规
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.RESIZE, Map.of("width", 100)),
                new DagNode("n2", PixelTool.SET_BACKGROUND, Map.of("color", "#FFFFFF")),
                new DagNode("n3", PixelTool.REMOVE_BG, Map.of())
            ),
            List.of(
                new DagEdge("n1", "n2"),
                new DagEdge("n2", "n3")
            )
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_OP_ORDER"));
    }

    @Test
    void validate_removeBg_first_succeeds() {
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.REMOVE_BG, Map.of()),
                new DagNode("n2", PixelTool.RESIZE, Map.of("width", 100))
            ),
            List.of(new DagEdge("n1", "n2"))
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void validate_invalidParams_fails() {
        // resize 缺必填 width/height
        DagDocument doc = new DagDocument(
            List.of(new DagNode("n1", PixelTool.RESIZE, Map.of())),
            List.of()
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_PARAMS"));
    }

    @Test
    void validate_compressQualityOutOfRange_fails() {
        DagDocument doc = new DagDocument(
            List.of(new DagNode("n1", PixelTool.COMPRESS, Map.of("quality", 200))),
            List.of()
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_PARAMS"));
    }

    @Test
    void validate_multipleComposeGroup_fails() {
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.RESIZE, Map.of("width", 100)),
                new DagNode("n2", PixelTool.RESIZE, Map.of("width", 100)),
                new DagNode("c1", PixelTool.COMPOSE_GROUP, Map.of("layout", "HORIZONTAL")),
                new DagNode("c2", PixelTool.COMPOSE_GROUP, Map.of("layout", "VERTICAL"))
            ),
            List.of(
                new DagEdge("n1", "c1"),
                new DagEdge("n2", "c2")
            )
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_GROUP_BRANCH") && e.contains("多个"));
    }

    @Test
    void validate_composeGroupWithNonOneToOnePredecessor_fails() {
        // compose_group 的前驱是 generate_copy(不是逐图工具),违规
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.GENERATE_COPY, Map.of("style", "SHORT")),
                new DagNode("n2", PixelTool.RESIZE, Map.of("width", 100)),
                new DagNode("c", PixelTool.COMPOSE_GROUP, Map.of("layout", "HORIZONTAL"))
            ),
            List.of(
                new DagEdge("n1", "c"),
                new DagEdge("n2", "c")
            )
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("DAG_INVALID_GROUP_BRANCH") && e.contains("非逐图"));
    }

    @Test
    void validate_validComposeGroup_succeeds() {
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.RESIZE, Map.of("width", 100)),
                new DagNode("n2", PixelTool.RESIZE, Map.of("width", 100)),
                new DagNode("c", PixelTool.COMPOSE_GROUP, Map.of("layout", "HORIZONTAL")),
                new DagNode("p", PixelTool.COMPRESS, Map.of("quality", 80))
            ),
            List.of(
                new DagEdge("n1", "c"),
                new DagEdge("n2", "c"),
                new DagEdge("c", "p")
            )
        );
        DagValidationResult result = validator.validate(doc);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void validate_isRepeatable_sameInput_sameResult() {
        DagDocument doc = new DagDocument(
            List.of(
                new DagNode("n1", PixelTool.REMOVE_BG, Map.of()),
                new DagNode("n2", PixelTool.RESIZE, Map.of("width", 800))
            ),
            List.of(new DagEdge("n1", "n2"))
        );
        DagValidationResult first = validator.validate(doc);
        DagValidationResult second = validator.validate(doc);
        assertThat(first.ok()).isEqualTo(second.ok());
        assertThat(first.errors()).isEqualTo(second.errors());
    }

    @Test
    void rules_implementNameContract() {
        // 防御性:确保所有规则对象都能 name() 调用
        ParamSchemaRegistry registry = new ParamSchemaRegistry();
        assertThat(new StructureRule().name()).isEqualTo("STRUCTURE");
        assertThat(new NodeLimitRule(50, 1).name()).isEqualTo("NODE_LIMIT");
        assertThat(new WhitelistRule().name()).isEqualTo("WHITELIST");
        assertThat(new ParamsRule(registry).name()).isEqualTo("PARAMS");
        assertThat(new EdgeRule().name()).isEqualTo("EDGE");
        assertThat(new AcyclicRule().name()).isEqualTo("ACYCLIC");
        assertThat(new GroupBranchRule().name()).isEqualTo("GROUP_BRANCH");
        assertThat(new OpOrderRule().name()).isEqualTo("OP_ORDER");
    }
}