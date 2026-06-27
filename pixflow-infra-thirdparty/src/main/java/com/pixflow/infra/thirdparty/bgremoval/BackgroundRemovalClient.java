package com.pixflow.infra.thirdparty.bgremoval;

/**
 * 供应商无关的背景去除能力入口。
 */
public interface BackgroundRemovalClient {
    BackgroundRemovalResult remove(BackgroundRemovalRequest request);
}
