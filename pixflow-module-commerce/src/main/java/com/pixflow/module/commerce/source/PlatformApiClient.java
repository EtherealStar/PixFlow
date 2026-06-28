package com.pixflow.module.commerce.source;

public interface PlatformApiClient {
    PlatformPullResult pull(PlatformPullRequest request);
}
