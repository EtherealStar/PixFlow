package com.pixflow.module.vision.image;

import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.vision.analyze.VisionImageRef;
import java.io.InputStream;
import java.util.Objects;

/**
 * 把 vision 图片引用解析为对象存储流。调用方负责关闭返回流。
 */
public class VisionImageResolver {
    private final ObjectStorage objectStorage;

    public VisionImageResolver(ObjectStorage objectStorage) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    public ResolvedVisionImage resolve(VisionImageRef ref) {
        Objects.requireNonNull(ref, "ref");
        StoredObjectMetadata metadata = objectStorage.stat(ref.object());
        InputStream stream = objectStorage.getStream(ref.object());
        return new ResolvedVisionImage(ref, stream, metadata.size(), metadata.contentType());
    }
}
