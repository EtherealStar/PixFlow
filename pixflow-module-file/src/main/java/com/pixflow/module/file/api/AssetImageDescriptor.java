package com.pixflow.module.file.api;

import com.pixflow.infra.storage.ObjectLocation;

/** READY 图片对可信 adapter 暴露的读取描述，不泄漏 File persistence。 */
public record AssetImageDescriptor(long imageId, long packageId, String skuId,
                                   String viewId, String groupKey,
                                   AssetSourceType sourceType,
                                   ObjectLocation location, String contentType) {
}
