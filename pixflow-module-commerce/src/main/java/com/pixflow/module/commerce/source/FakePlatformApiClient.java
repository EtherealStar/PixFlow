package com.pixflow.module.commerce.source;

import java.util.List;

/**
 * 默认 fake 实现只用于本地验证和测试占位，不绑定任何真实平台。
 */
public class FakePlatformApiClient implements PlatformApiClient {
    @Override
    public PlatformPullResult pull(PlatformPullRequest request) {
        return new PlatformPullResult(List.of());
    }
}
