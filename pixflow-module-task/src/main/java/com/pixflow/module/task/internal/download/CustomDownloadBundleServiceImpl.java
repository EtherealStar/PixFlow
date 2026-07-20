package com.pixflow.module.task.internal.download;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.api.download.CustomDownloadBundleService;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import com.pixflow.module.task.api.query.DownloadHandle;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import java.net.URL;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CustomDownloadBundleServiceImpl implements CustomDownloadBundleService {
  private final DownloadBundleBuilder bundleBuilder;

  private final ObjectStorage objectStorage;

  private final TaskProperties properties;

  private final Clock clock;

  private final PublishedAssetReader publishedAssets;

  public CustomDownloadBundleServiceImpl(
      DownloadBundleBuilder bundleBuilder,
      ObjectStorage objectStorage,
      TaskProperties properties,
      Clock clock,
      PublishedAssetReader publishedAssets) {
    this.bundleBuilder = bundleBuilder;
    this.objectStorage = objectStorage;
    this.properties = properties;
    this.clock = clock;
    this.publishedAssets = publishedAssets;
  }

  @Override
  public DownloadHandle build(String archiveName, List<BundleItem> items) {
    if (items == null || items.isEmpty()) {
      throw new PixFlowException(
          TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "download bundle requires at least one item");
    }
    var internalSources = items.stream()
            .map(item -> bundleSource(item, publishedAssets.require(item.referenceKey())))
            .toList();
    String archiveKey =
        "custom-downloads/" + UUID.randomUUID() + "/" + normalizeArchiveName(archiveName);
    var ref = bundleBuilder.build(archiveKey, internalSources);
    URL url =
        objectStorage.presignGet(
            ObjectLocation.of(BucketType.TMP, ref.key()),
            properties.getDownload().getSingleUrlExpiry());
    return new DownloadHandle(
        url,
        clock.instant().plus(properties.getDownload().getSingleUrlExpiry()),
        "application/zip",
        ref.size());
  }

  private static DownloadBundleBuilder.BundleSource bundleSource(
      BundleItem item, PublishedAssetReader.PublishedAssetContent content) {
    String requested = item.requestedFilename();
    String filename = requested == null || requested.isBlank()
        ? content.imageId() + ".image" : requested.trim();
    return new DownloadBundleBuilder.BundleSource(filename, content::open);
  }

  private static String normalizeArchiveName(String name) {
    String selected = name == null || name.isBlank() ? "selected-images.zip" : name.trim();
    String normalized = selected.replaceAll("[^A-Za-z0-9_.-]", "_");
    return normalized.toLowerCase(Locale.ROOT).endsWith(".zip")
        ? normalized
        : normalized + ".zip";
  }
}
