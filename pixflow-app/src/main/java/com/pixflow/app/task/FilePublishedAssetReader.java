package com.pixflow.app.task;

import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.api.AssetImageQuery;
import com.pixflow.module.task.api.publication.PublishedAssetReader;

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
  public PublishedAssetContent require(String referenceKey) {
    var parsed = codec.parse(referenceKey);
    if (!(parsed instanceof ImageAssetReferenceKey imageReference)) {
      throw new IllegalArgumentException("referenceKey must identify an image");
    }
    var image = images.require(imageReference.packageId(), imageReference.imageId());
    long size = storage.stat(image.location()).size();
    return new PublishedAssetContent(image.imageId(), image.location(), image.contentType(), size);
  }
}
