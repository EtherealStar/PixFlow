package com.pixflow.module.file.runtime;

import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.file.api.AssetSourceType;

/** 可信组合根使用的运行时位置，不属于 Asset Library 的公开产品 DTO。 */
public record AssetImageDescriptor(long imageId, long packageId, String skuId,
                                   String viewId, String groupKey,
                                   AssetSourceType sourceType,
                                   ObjectLocation location, String contentType) {
}
