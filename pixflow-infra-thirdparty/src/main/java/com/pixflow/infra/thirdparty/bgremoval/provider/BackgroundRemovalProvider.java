package com.pixflow.infra.thirdparty.bgremoval.provider;

import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalClient;
import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalRequest;
import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalResult;

/**
 * provider SPI：核心只依赖这个接口，具体供应商实现可插拔。
 */
public interface BackgroundRemovalProvider extends BackgroundRemovalClient {
    String providerId();

    default String capability() {
        return "bg-removal";
    }

    @Override
    BackgroundRemovalResult remove(BackgroundRemovalRequest request);
}
