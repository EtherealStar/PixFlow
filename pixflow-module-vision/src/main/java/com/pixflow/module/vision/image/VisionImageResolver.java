package com.pixflow.module.vision.image;

import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.vision.analyze.VisionImageRef;
import java.util.Objects;

/**
 * 把 vision 图片引用解析为可重开的对象存储来源，供 probe 与 decode 分别读取。
 */
public class VisionImageResolver {
    private final ObjectStorage objectStorage;

    public VisionImageResolver(ObjectStorage objectStorage) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    public ResolvedVisionImage resolve(VisionImageRef ref) {
        Objects.requireNonNull(ref, "ref");
        StoredObjectMetadata metadata = objectStorage.stat(ref.object());
        return new ResolvedVisionImage(ref, () -> objectStorage.getStream(ref.object()),
                metadata.size(), metadata.contentType());
    }
}
