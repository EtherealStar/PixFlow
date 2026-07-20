package com.pixflow.module.file.api;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;

public interface AssetContentReader {
    AssetContentMetadata require(String referenceKey);

    AssetContentMetadata require(long packageId, long imageId);

    List<AssetContentMetadata> listCurrentOriginals(long packageId);

    /** 返回可参与任务执行的 READY 图片；调用方只能持有 canonical reference。 */
    List<AssetContentMetadata> listReady(long packageId);

    InputStream open(String referenceKey);

    URL presign(String referenceKey, Duration ttl);
}
