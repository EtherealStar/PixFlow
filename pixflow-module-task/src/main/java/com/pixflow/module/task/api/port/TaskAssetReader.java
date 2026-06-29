package com.pixflow.module.task.api.port;

import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.dag.expand.ImageDescriptor;
import java.util.List;

public interface TaskAssetReader {
    List<ImageDescriptor> listImages(long packageId);

    GenerativeSource sourceImage(long packageId, String sourceImageId);

    record GenerativeSource(String sourceImageId, String skuId, ObjectLocation location) {
        public GenerativeSource {
            if (sourceImageId == null || sourceImageId.isBlank()) {
                throw new IllegalArgumentException("sourceImageId must not be blank");
            }
            if (skuId == null || skuId.isBlank()) {
                throw new IllegalArgumentException("skuId must not be blank");
            }
            if (location == null) {
                throw new IllegalArgumentException("location must not be null");
            }
        }
    }
}
