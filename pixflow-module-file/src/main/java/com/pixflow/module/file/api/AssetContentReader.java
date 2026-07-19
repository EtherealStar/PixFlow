package com.pixflow.module.file.api;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

public interface AssetContentReader {
    AssetContentMetadata require(String referenceKey);

    InputStream open(String referenceKey);

    URL presign(String referenceKey, Duration ttl);
}
