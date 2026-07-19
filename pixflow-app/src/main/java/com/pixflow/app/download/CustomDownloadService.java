package com.pixflow.app.download;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.runtime.AssetImageQuery;
import com.pixflow.module.task.api.download.CustomDownloadBundleService;
import com.pixflow.module.task.api.download.PublishedTaskResultQuery;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import com.pixflow.module.task.api.query.DownloadHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CustomDownloadService {
  private final AssetImageQuery assetImages;

  private final PublishedAssetReader publishedAssets;

  private final PublishedTaskResultQuery taskResults;

  private final CustomDownloadBundleService bundleService;

  public CustomDownloadService(
      AssetImageQuery assetImages,
      PublishedAssetReader publishedAssets,
      PublishedTaskResultQuery taskResults,
      CustomDownloadBundleService bundleService) {
    this.assetImages = assetImages;
    this.publishedAssets = publishedAssets;
    this.taskResults = taskResults;
    this.bundleService = bundleService;
  }

  public DownloadHandle build(CustomBundleRequest request) {
    if (request == null || request.items() == null || request.items().isEmpty()) {
      throw new PixFlowException(
          CommonErrorCode.INVALID_PARAM, "download bundle requires at least one item");
    }
    List<CustomDownloadBundleService.BundleSource> sources = new ArrayList<>();
    for (CustomBundleRequest.BundleItem item : request.items()) {
      sources.add(resolve(item));
    }
    return bundleService.build(request.archiveName(), sources);
  }

  private CustomDownloadBundleService.BundleSource resolve(CustomBundleRequest.BundleItem item) {
    String type = item.type() == null ? "" : item.type().toUpperCase(Locale.ROOT);
    if ("ASSET_IMAGE".equals(type)) {
      final var image = assetImages.require(parseId(item.imageId()));
      if (image.location() == null) {
        throw new PixFlowException(
            CommonErrorCode.BUSINESS_RULE_VIOLATION, "asset image is not ready for bundle");
      }
      return new CustomDownloadBundleService.BundleSource(
          firstNonBlank(item.filename(), image.imageId() + ".image"), image.location());
    }
    if ("TASK_RESULT".equals(type)) {
      var result = taskResults.require(parseId(item.resultId()));
      var content = publishedAssets.require(result.referenceKey());
      return new CustomDownloadBundleService.BundleSource(
          firstNonBlank(item.filename(), result.displayName(), result.resultId() + ".image"),
          content.location());
    }
    throw new PixFlowException(
        CommonErrorCode.INVALID_PARAM, "unknown download bundle item type: " + item.type());
  }

  private static long parseId(String id) {
    try {
      return Long.parseLong(id);
    } catch (RuntimeException ex) {
      throw new PixFlowException(
          CommonErrorCode.INVALID_PARAM, "invalid download bundle item id", ex);
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "image";
  }
}
