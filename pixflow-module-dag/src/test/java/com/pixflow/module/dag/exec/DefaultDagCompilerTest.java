package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.dag.ir.CanonicalDag;
import com.pixflow.module.dag.ir.DagEdge;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.PixelTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultDagCompilerTest {
    @Test
    void compilesNodesToTypedStepsWithoutRawDispatcherFallback() {
        CanonicalDag dag = new CanonicalDag(new byte[] {1}, "hash", "1.0",
                List.of(new DagNode("resize", PixelTool.RESIZE,
                                Map.of("width", 100, "height", 80)),
                        new DagNode("remove", PixelTool.REMOVE_BG, Map.of())),
                List.of(new DagEdge("resize", "remove")));

        TypedExecutionPlan plan = new DefaultDagCompiler(new StepSpecCompiler()).compile(dag);

        assertThat(plan.canonicalHash()).isEqualTo("hash");
        assertThat(plan.steps()).extracting(ExecutionStep::nodeId)
                .containsExactly("resize", "remove");
        assertThat(plan.steps().get(0)).isInstanceOf(LocalImageStep.class);
        assertThat(plan.steps().get(1)).isInstanceOf(ExternalStep.class);
        assertThat(((LocalImageStep) plan.steps().get(0)).typedSpec()).isInstanceOf(
                LocalImageBindingSpec.Resize.class);
    }
}
