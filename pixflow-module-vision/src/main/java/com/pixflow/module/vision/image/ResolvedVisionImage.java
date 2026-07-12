package com.pixflow.module.vision.image;

import com.pixflow.module.vision.analyze.VisionImageRef;
import com.pixflow.infra.image.ReopenableImageSource;
import java.util.Objects;

public record ResolvedVisionImage(VisionImageRef ref, ReopenableImageSource source, long sizeBytes, String contentType) {
    public ResolvedVisionImage {
        ref = Objects.requireNonNull(ref, "ref");
        source = Objects.requireNonNull(source, "source");
    }
}
