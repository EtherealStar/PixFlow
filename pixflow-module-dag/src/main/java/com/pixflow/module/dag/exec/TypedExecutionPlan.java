package com.pixflow.module.dag.exec;
import java.util.List;
public record TypedExecutionPlan(String canonicalHash, String schemaVersion,
                                 List<ExecutionStep> steps, List<ExecutionEdge> edges) {
    public TypedExecutionPlan { steps = List.copyOf(steps); edges = List.copyOf(edges); }
}
