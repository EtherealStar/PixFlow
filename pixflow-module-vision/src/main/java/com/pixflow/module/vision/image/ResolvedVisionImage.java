package com.pixflow.module.vision.image;

import com.pixflow.module.vision.analyze.VisionImageRef;
import java.io.InputStream;
import java.util.Objects;

public record ResolvedVisionImage(VisionImageRef ref, InputStream stream, long sizeBytes, String contentType) {
    public ResolvedVisionImage {
        ref = Objects.requireNonNull(ref, "ref");
        stream = Objects.requireNonNull(stream, "stream");
    }
}
