package com.pixflow.app.download;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.api.download.CustomDownloadBundleService;
import com.pixflow.module.task.api.query.DownloadHandle;

public class CustomDownloadService {
  private final CustomDownloadBundleService bundleService;

  public CustomDownloadService(CustomDownloadBundleService bundleService) {
    this.bundleService = bundleService;
  }

  public DownloadHandle build(CustomBundleRequest request) {
    if (request == null || request.items() == null || request.items().isEmpty()) {
      throw new PixFlowException(
          CommonErrorCode.INVALID_PARAM, "download bundle requires at least one item");
    }
    var items = request.items().stream()
        .map(item -> new CustomDownloadBundleService.BundleItem(
            item.referenceKey(), item.filename()))
        .toList();
    return bundleService.build(request.archiveName(), items);
  }
}
