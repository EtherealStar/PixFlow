package com.pixflow.module.dag;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.GroupPreflight.PreflightDifference;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.GroupPreflight;
import com.pixflow.module.dag.validate.DagValidationResult;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DagFacade 对外面门测试。
 */
class DagFacadeTest {

    private DagFacade facade;
    private DagJsonReader reader;

    @BeforeEach
    void setUp() {
        facade = new DagFacade(
            new DagValidator(new ParamSchemaRegistry(), 50, 1),
            new BranchExpander(),
            new GroupPreflight(),
            new com.pixflow.module.dag.ir.CanonicalDagFactory(new com.fasterxml.jackson.databind.ObjectMapper()),
            new com.pixflow.module.dag.exec.DefaultDagCompiler(new com.pixflow.module.dag.exec.StepSpecCompiler())
        );
        reader = new DagJsonReader();
    }

    @Test
    void validate_returnsSuccess_onValidDag() {
        DagDocument doc = reader.read("""
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800}}],"edges":[]}
            """);
        DagValidationResult result = facade.validate(doc);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void validateToCanonical_returnsCanonicalDag_onValidInput() {
        DagDocument doc = reader.read("""
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800}}],"edges":[]}
            """);
        var dag = facade.validateToCanonical(doc, new DagSchemaVersion("1.0"));
        assertThat(dag.nodes()).hasSize(1);
    }

    @Test
    void expand_returnsBranches() {
        DagDocument doc = reader.read("""
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800}}],"edges":[]}
            """);
        var dag = facade.compile(facade.validateToCanonical(doc, new DagSchemaVersion("1.0")));
        List<ExecutableBranch> branches = facade.expand(dag,
            List.of(ImageDescriptor.single("img1", "sku1",
                    "asset:image:k1")));
        assertThat(branches).hasSize(1);
    }

    @Test
    void preflightGroups_returnsDifferences() {
        DagDocument doc = reader.read("""
            {
              "nodes":[
                {"id":"c","tool":"compose_group","params":{"layout":"GRID","expected_count":3}}
              ],
              "edges":[]
            }
            """);
        var dag = facade.compile(facade.validateToCanonical(doc, new DagSchemaVersion("1.0")));
        List<PreflightDifference> diffs = facade.preflightGroups(dag, Map.of("c", 2));
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).expectedCount()).isEqualTo(3);
        assertThat(diffs.get(0).actualCount()).isEqualTo(2);
    }
}
