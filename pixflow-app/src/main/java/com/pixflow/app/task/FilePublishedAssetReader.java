package com.pixflow.app.task;

import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.runtime.AssetImageQuery;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import java.util.List;
import java.util.Optional;

/** Task-owned PublishedAssetReader 的 File public API adapter。 */
public final class FilePublishedAssetReader implements PublishedAssetReader {
  private final AssetImageQuery images;

  private final ObjectStorage storage;

  private final CanonicalAssetReferenceCodec codec;

  public FilePublishedAssetReader(
      AssetImageQuery images, ObjectStorage storage, CanonicalAssetReferenceCodec codec) {
    this.images = images;
    this.storage = storage;
    this.codec = codec;
  }

  @Override
  public Optional<PublishedAssetContent> find(String referenceKey) {
    var parsed = codec.parse(referenceKey);
    if (!(parsed instanceof ImageAssetReferenceKey imageReference)) {
      throw new IllegalArgumentException("referenceKey must identify an image");
    }
    var image =
        images.findAll(imageReference.packageId(), List.of(imageReference.imageId())).stream()
            .findFirst();
    if (image.isEmpty()) {
      return Optional.empty();
    }
    var descriptor = image.get();
    long size = storage.stat(descriptor.location()).size();
    return Optional.of(
        new PublishedAssetContent(
            descriptor.imageId(), descriptor.location(), descriptor.contentType(), size));
  }
}
