package com.pixflow.module.dag.expand;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.exec.TypedExecutionPlan;
import com.pixflow.module.dag.TestPlans;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GroupPreflight 纯函数测试:expected_count vs 实际成员数差异。
 */
class GroupPreflightTest {

    private GroupPreflight preflight;
    private DagValidator validator;
    private final DagJsonReader reader = new DagJsonReader();

    @BeforeEach
    void setUp() {
        preflight = new GroupPreflight();
        validator = new DagValidator(new ParamSchemaRegistry(), 50, 1);
    }

    private TypedExecutionPlan parse(String json) {
        return TestPlans.compile(json);
    }

    @Test
    void noExpectedCount_producesNoDiffs() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"c","tool":"compose_group","params":{"layout":"HORIZONTAL"}}
              ],
              "edges":[]
            }
            """);
        var diffs = preflight.preflight(dag, Map.of("c", 5));
        assertThat(diffs).isEmpty();
    }

    @Test
    void expectedMatchesActual_producesNoDiffs() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"c","tool":"compose_group","params":{"layout":"HORIZONTAL","expected_count":3}}
              ],
              "edges":[]
            }
            """);
        var diffs = preflight.preflight(dag, Map.of("c", 3));
        assertThat(diffs).isEmpty();
    }

    @Test
    void expectedGreaterThanActual_producesDiff() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"c","tool":"compose_group","params":{"layout":"HORIZONTAL","expected_count":3}}
              ],
              "edges":[]
            }
            """);
        var diffs = preflight.preflight(dag, Map.of("c", 2));
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).expectedCount()).isEqualTo(3);
        assertThat(diffs.get(0).actualCount()).isEqualTo(2);
    }

    @Test
    void expectedLessThanActual_producesDiff() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"c","tool":"compose_group","params":{"layout":"GRID","expected_count":2}}
              ],
              "edges":[]
            }
            """);
        var diffs = preflight.preflight(dag, Map.of("c", 5));
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).expectedCount()).isEqualTo(2);
        assertThat(diffs.get(0).actualCount()).isEqualTo(5);
    }

    @Test
    void missingActualCount_producesDiff() {
        TypedExecutionPlan dag = parse("""
            {
              "nodes":[
                {"id":"c","tool":"compose_group","params":{"layout":"HORIZONTAL","expected_count":3}}
              ],
              "edges":[]
            }
            """);
        var diffs = preflight.preflight(dag, Map.of());
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).actualCount()).isEqualTo(0);
    }
}
