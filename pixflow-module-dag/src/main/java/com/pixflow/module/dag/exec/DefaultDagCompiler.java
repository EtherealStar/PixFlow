package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.ir.CanonicalDag;
import java.util.List;
import java.util.Objects;

public final class DefaultDagCompiler implements DagCompiler {
    private final StepSpecCompiler specCompiler;

    private final StepBindingRegistry bindings;

    public DefaultDagCompiler(StepSpecCompiler specCompiler) {
        this(specCompiler, new StepBindingRegistry());
    }

    public DefaultDagCompiler(StepSpecCompiler specCompiler, StepBindingRegistry bindings) {
        this.specCompiler = Objects.requireNonNull(specCompiler);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override public TypedExecutionPlan compile(CanonicalDag dag) {
        List<ExecutionStep> steps = dag.nodes().stream().map(node -> switch (bindings.require(node.tool())) {
            case LOCAL_IMAGE -> new LocalImageStep(node.id(), node.tool(),
                    LocalImageBindingSpec.from(node.tool(), specCompiler.compile(node)));
            case GROUP -> new GroupStep(node.id(), node.tool(),
                    (com.pixflow.infra.image.op.ComposeSpec) specCompiler.compile(node),
                    node.params().get("expected_count") instanceof Number count ? count.intValue() : 0);
            // 外部调用和文案执行同样只能读取编译产物，不能在 worker 中回退到原始参数 map。
            case EXTERNAL -> new ExternalStep(node.id(), node.tool(),
                    (BackgroundRemovalBindingSpec) specCompiler.compile(node));
            case COPY -> new CopyStep(node.id(), node.tool(),
                    (CopyBindingSpec) specCompiler.compile(node));
        }).map(step -> (ExecutionStep) step).toList();
        List<ExecutionEdge> edges = dag.edges().stream()
            .map(edge -> new ExecutionEdge(edge.from(), edge.to())).toList();
        return new TypedExecutionPlan(dag.canonicalHash(), dag.schemaVersion(), steps, edges);
    }
}
