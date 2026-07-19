package com.pixflow.module.task.api.download;

import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.task.api.query.DownloadHandle;
import java.util.List;

/** 为可信应用层已解析的图片来源构建受大小限制的临时 ZIP。 */
public interface CustomDownloadBundleService {
  DownloadHandle build(String archiveName, List<BundleSource> sources);

  record BundleSource(String entryName, ObjectLocation location) {
    public BundleSource {
      if (entryName == null || entryName.isBlank()) {
        throw new IllegalArgumentException("entryName must not be blank");
      }
      if (location == null) {
        throw new IllegalArgumentException("location must not be null");
      }
    }
  }
}
