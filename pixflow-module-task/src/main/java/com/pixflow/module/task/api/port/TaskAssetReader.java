package com.pixflow.module.task.api.port;

import com.pixflow.module.dag.expand.ImageDescriptor;
import java.util.List;

public interface TaskAssetReader {
  List<ImageDescriptor> listImages(long packageId);

  GenerativeSource sourceImage(long packageId, String sourceImageId);

  record GenerativeSource(String sourceImageId, String skuId, String referenceKey, long sizeBytes) {
    public GenerativeSource {
      if (sourceImageId == null || sourceImageId.isBlank()) {
        throw new IllegalArgumentException("sourceImageId must not be blank");
      }
      if (skuId == null || skuId.isBlank()) {
        throw new IllegalArgumentException("skuId must not be blank");
      }
      if (referenceKey == null || referenceKey.isBlank()) {
        throw new IllegalArgumentException("referenceKey must not be blank");
      }
      if (sizeBytes < 0) {
        throw new IllegalArgumentException("sizeBytes must not be negative");
      }
    }
  }
}
