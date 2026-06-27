package com.pixflow.infra.image.pipeline;

import com.pixflow.infra.image.op.ImageOp;

public record PipelineStep(String name, ImageOp op) {
    public PipelineStep {
        if (op == null) {
            throw new IllegalArgumentException("op must not be null");
        }
        name = name == null || name.isBlank() ? op.getClass().getSimpleName() : name;
    }
}
