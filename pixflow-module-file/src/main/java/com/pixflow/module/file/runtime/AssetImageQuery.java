package com.pixflow.module.file.runtime;

import java.util.List;

/** 仅供组合根冻结 Task 运行输入的可信位置查询；HTTP 与工具边界不得暴露此类型。 */
public interface AssetImageQuery {
    List<AssetImageDescriptor> listReady(long packageId);

    List<AssetImageDescriptor> findAll(long packageId, List<Long> imageIds);

    AssetImageDescriptor require(long packageId, long imageId);

    AssetImageDescriptor require(long imageId);
}
