package com.pixflow.module.dag.exec;
import com.pixflow.module.dag.ir.PixelTool;
public record LocalImageStep(String nodeId, PixelTool tool,
                             LocalImageBindingSpec typedSpec) implements ExecutionStep {}
