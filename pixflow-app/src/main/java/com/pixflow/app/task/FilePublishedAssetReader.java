package com.pixflow.app.task;

import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.api.AssetContentMetadata;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import java.util.Optional;

/** Task-owned PublishedAssetReader 的 File public API adapter。 */
public final class FilePublishedAssetReader implements PublishedAssetReader {
  private final AssetContentReader contents;

  public FilePublishedAssetReader(AssetContentReader contents) {
    this.contents = contents;
  }

  @Override
  public Optional<PublishedAssetContent> find(String referenceKey) {
    final AssetContentMetadata image;
    try {
      image = contents.require(referenceKey);
    } catch (RuntimeException missing) {
      return Optional.empty();
    }
    ContentAccess access = new ContentAccess() {
      @Override
      public java.io.InputStream open() {
        return contents.open(referenceKey);
      }

      @Override
      public java.net.URL presign(java.time.Duration ttl) {
        return contents.presign(referenceKey, ttl);
      }
    };
    return Optional.of(new PublishedAssetContent(
        image.imageId(), image.contentType(), image.size(), access));
  }
}
