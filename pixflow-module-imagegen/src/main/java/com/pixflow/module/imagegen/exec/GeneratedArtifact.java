package com.pixflow.module.imagegen.exec;

import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.imagegen.ImageProducer;
import com.pixflow.infra.storage.ObjectRef;
import java.util.Objects;

/**
 * 生图产物(对齐 imagegen.md §8.2)。
 *
 * <p>由 {@link ImageGenExecutor#redraw(GenerativeUnitSpec)} 返回,
 * 由 {@code module/task} 据此写 process_result(产出 minio_key、usage、status 等)。
 */
public record GeneratedArtifact(
        ObjectRef output,
        String contentType,
        TokenUsage usage,
        ImageProducer producer) {

    public GeneratedArtifact {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(producer, "producer");
    }
}
