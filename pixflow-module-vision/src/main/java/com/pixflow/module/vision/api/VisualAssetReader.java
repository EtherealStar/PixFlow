package com.pixflow.module.vision.api;

import java.util.List;

/**
 * Vision 属主定义的资产读取边界，由组合根适配 File 与 Storage。
 */
public interface VisualAssetReader {
    List<VisualAsset> listCurrentOriginals(long packageId);

    VisualAsset requireImage(long packageId, long imageId);
}
