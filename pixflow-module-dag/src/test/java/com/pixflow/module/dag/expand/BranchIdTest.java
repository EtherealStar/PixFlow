package com.pixflow.module.dag.expand;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.dag.ir.CanonicalJson;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BranchId 确定性测试:同语义 DAG 多次展开得到相同 branchId。
 */
class BranchIdTest {

    private final DagJsonReader reader = new DagJsonReader();
    private final DagValidator validator =
        new DagValidator(new ParamSchemaRegistry(), 50, 1);

    @Test
    void branchId_isStable_acrossFieldReordering() {
        String json1 = """
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800,"height":600}}],
             "edges":[]}
            """;
        String json2 = """
            {"nodes":[{"id":"n1","tool":"resize","params":{"height":600,"width":800}}],
             "edges":[]}
            """;
        String id1 = BranchId.derive(List.of("n1"),
            List.of(Map.of("width", 800, "height", 600)));
        String id2 = BranchId.derive(List.of("n1"),
            List.of(Map.of("height", 600, "width", 800)));
        assertThat(id1).isEqualTo(id2);
        // 防止空 hash
        assertThat(id1).hasSize(64);
    }

    @Test
    void branchId_differsForDifferentParams() {
        String id1 = BranchId.derive(List.of("n1"),
            List.of(Map.of("width", 800)));
        String id2 = BranchId.derive(List.of("n1"),
            List.of(Map.of("width", 1200)));
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void branchId_differsForDifferentPaths() {
        String id1 = BranchId.derive(List.of("n1", "n2"),
            List.of(Map.of("x", 1), Map.of("y", 2)));
        String id2 = BranchId.derive(List.of("n1", "n3"),
            List.of(Map.of("x", 1), Map.of("y", 2)));
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void branchId_isSameWhenPathAndParamsMatch() {
        String id1 = BranchId.derive(List.of("n1"),
            List.of(Map.of("width", 800)));
        String id2 = BranchId.derive(List.of("n1"),
            List.of(Map.of("width", 800)));
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void branchId_usesCanonicalForm() {
        // 通过 CanonicalJson 间接验证:不同 Map 顺序但内容相同应同 hash
        Object params = CanonicalJson.normalize(Map.of("a", 1, "b", 2));
        Object params2 = CanonicalJson.normalize(Map.of("b", 2, "a", 1));
        assertThat(CanonicalJson.canonicalize(params))
            .isEqualTo(CanonicalJson.canonicalize(params2));
    }
}
