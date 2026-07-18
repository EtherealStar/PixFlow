package com.pixflow.module.dag.expand;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.exec.TypedExecutionPlan;
import com.pixflow.module.dag.TestPlans;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BranchExpander 纯函数测试:覆盖分支/组支路展开、确定性 branchId、ImageDescriptor 驱动。
 */
class BranchExpanderTest {

    private DagValidator validator;
    private BranchExpander expander;
    private final DagJsonReader reader = new DagJsonReader();

    @BeforeEach
    void setUp() {
        validator = new DagValidator(new ParamSchemaRegistry(), 50, 1);
        expander = new BranchExpander();
    }

    private TypedExecutionPlan parse(String json) {
        return TestPlans.compile(json);
    }

    @Test
    void expand_singleNode_producesOneBranchPerImage() {
        TypedExecutionPlan dag = parse("""
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800}}],"edges":[]}
            """);
        List<ImageDescriptor> images = List.of(
            ImageDescriptor.single("i1", "sku1", location("p1/img1.jpg")),
            ImageDescriptor.single("i2", "sku1", location("p1/img2.jpg"))
        );
        var branches = expander.expand(dag, images);
        assertThat(branches).hasSize(2);
        assertThat(branches).allSatisfy(b -> {
            assertThat(b.kind()).isEqualTo(com.pixflow.harness.state.model.UnitKind.BRANCH);
            assertThat(b.composeStep()).isNull();
            assertThat(b.perMemberOps()).hasSize(1);
            assertThat(b.perMemberOps().get(0).tool()).isEqualTo(PixelTool.RESIZE);
        });
        assertThat(branches).extracting(ExecutableBranch::memberId)
            .containsExactlyInAnyOrder("i1", "i2");
    }

    @Test
    void expand_linearChain_producesLinearOps() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"n1","tool":"remove_bg","params":{}},
                {"id":"n2","tool":"set_background","params":{"color":"#FFFFFF"}},
                {"id":"n3","tool":"resize","params":{"width":800,"height":800}}
              ],
              "edges":[{"from":"n1","to":"n2"},{"from":"n2","to":"n3"}]
            }
            """);
        var branches = expander.expand(dag, List.of(ImageDescriptor.single("i1", "sku1", location("k"))));
        assertThat(branches).hasSize(1);
        ExecutableBranch b = branches.get(0);
        assertThat(b.perMemberOps()).extracting(n -> n.nodeId())
            .containsExactly("n1", "n2", "n3");
    }

    @Test
    void expand_branchingGraph_producesMultiplePaths() {
        // n1 → n2, n1 → n3(分叉):两条支路
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"n1","tool":"remove_bg","params":{}},
                {"id":"n2","tool":"resize","params":{"width":800}},
                {"id":"n3","tool":"resize","params":{"width":200}}
              ],
              "edges":[{"from":"n1","to":"n2"},{"from":"n1","to":"n3"}]
            }
            """);
        var branches = expander.expand(dag, List.of(ImageDescriptor.single("i1", "sku1", location("k"))));
        // 应展开为 2 条(每张图 × 2 路径)
        assertThat(branches).hasSize(2);
        // 不同 branchId
        assertThat(branches.get(0).branchId()).isNotEqualTo(branches.get(1).branchId());
    }

    @Test
    void expand_composeGroup_producesPerMemberComposeAndPost() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"n1","tool":"resize","params":{"width":100}},
                {"id":"n2","tool":"resize","params":{"width":100}},
                {"id":"c","tool":"compose_group","params":{"layout":"HORIZONTAL"}},
                {"id":"p","tool":"compress","params":{"quality":80}}
              ],
              "edges":[
                {"from":"n1","to":"c"},
                {"from":"n2","to":"c"},
                {"from":"c","to":"p"}
              ]
            }
            """);
        var groups = List.of(
            ImageDescriptor.grouped("i1", "g1", "v1", location("k1")),
            ImageDescriptor.grouped("i2", "g1", "v2", location("k2"))
        );
        var branches = expander.expand(dag, groups);
        var groupBranch = branches.stream()
            .filter(b -> b.kind() == com.pixflow.harness.state.model.UnitKind.GROUP)
            .findFirst().orElseThrow();
        assertThat(groupBranch.perMemberOps()).hasSize(2);
        assertThat(groupBranch.composeStep()).isNotNull();
        assertThat(groupBranch.composeStep().nodeId()).isEqualTo("c");
        assertThat(groupBranch.postOps()).hasSize(1);
        assertThat(groupBranch.postOps().get(0).nodeId()).isEqualTo("p");
    }

    @Test
    void expand_determinism_sameInput_sameBranches() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"n1","tool":"remove_bg","params":{}},
                {"id":"n2","tool":"resize","params":{"width":800,"height":600}}
              ],
              "edges":[{"from":"n1","to":"n2"}]
            }
            """);
        var images = List.of(ImageDescriptor.single("i1", "sku1", location("k1")));
        var first = expander.expand(dag, images);
        var second = expander.expand(dag, images);
        assertThat(first).hasSize(second.size());
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).branchId()).isEqualTo(second.get(i).branchId());
        }
    }

    @Test
    void expand_encodeTarget_infersFromConvertFormat() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"n1","tool":"remove_bg","params":{}},
                {"id":"n2","tool":"convert_format","params":{"targetFormat":"WEBP","quality":80}}
              ],
              "edges":[{"from":"n1","to":"n2"}]
            }
            """);
        var branches = expander.expand(dag, List.of(ImageDescriptor.single("i1", "sku1", location("k"))));
        assertThat(branches.get(0).encode().format()).isEqualTo("WEBP");
        assertThat(branches.get(0).encode().quality()).isEqualTo(80);
    }

    @Test
    void expand_defaultEncodeTarget_isJpeg() {
        TypedExecutionPlan dag = parse("""
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800}}],"edges":[]}
            """);
        var branches = expander.expand(dag, List.of(ImageDescriptor.single("i1", "sku1", location("k"))));
        assertThat(branches.get(0).encode().format()).isEqualTo("JPEG");
        assertThat(branches.get(0).encode().quality()).isNull();
    }

    @Test
    void expand_emptyImages_noBranches() {
        TypedExecutionPlan dag = parse("""
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800}}],"edges":[]}
            """);
        var branches = expander.expand(dag, List.of());
        assertThat(branches).isEmpty();
    }

    @Test
    void expand_groupBranchMemberId_isGroupKey() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"n1","tool":"resize","params":{"width":100}},
                {"id":"c","tool":"compose_group","params":{"layout":"GRID"}}
              ],
              "edges":[{"from":"n1","to":"c"}]
            }
            """);
        var groups = List.of(
            ImageDescriptor.grouped("i1", "groupA", "v1", location("k1")),
            ImageDescriptor.grouped("i2", "groupA", "v2", location("k2"))
        );
        var branches = expander.expand(dag, groups);
        var groupBranch = branches.stream()
            .filter(b -> b.kind() == com.pixflow.harness.state.model.UnitKind.GROUP)
            .findFirst().orElseThrow();
        assertThat(groupBranch.memberId()).isEqualTo("groupA");
    }
    private static ObjectLocation location(String key) {
        return ObjectLocation.of(BucketType.PACKAGES, key);
    }
}
