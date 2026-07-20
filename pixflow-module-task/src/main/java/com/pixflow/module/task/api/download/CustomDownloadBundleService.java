package com.pixflow.module.task.api.download;

import com.pixflow.module.task.api.query.DownloadHandle;
import java.util.List;

/** 解析 canonical IMAGE references，并构建受大小限制的临时 ZIP。 */
public interface CustomDownloadBundleService {
  DownloadHandle build(String archiveName, List<BundleItem> items);

  record BundleItem(String referenceKey, String requestedFilename) {
    public BundleItem {
      if (referenceKey == null || referenceKey.isBlank()) {
        throw new IllegalArgumentException("referenceKey must not be blank");
      }
      referenceKey = referenceKey.trim();
    }
  }
}
