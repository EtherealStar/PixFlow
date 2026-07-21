package com.pixflow.module.task.api.publication;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;

/** Task 下载边界按 Published Asset identity 获取稳定内容位置。 */
public interface PublishedAssetReader {
  Optional<PublishedAssetContent> find(String referenceKey);

  default PublishedAssetContent require(String referenceKey) {
    return find(referenceKey)
        .orElseThrow(() -> new IllegalArgumentException("published asset not found"));
  }

  record PublishedAssetContent(
      long imageId, String contentType, String contentHash, long size, ContentAccess content) {
    public InputStream open() {
      return content.open();
    }

    public URL presign(Duration ttl) {
      return content.presign(ttl);
    }
  }

  interface ContentAccess {
    InputStream open();

    URL presign(Duration ttl);
  }
}
