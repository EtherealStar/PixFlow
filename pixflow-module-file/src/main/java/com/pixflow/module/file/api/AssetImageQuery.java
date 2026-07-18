package com.pixflow.module.file.api;

import java.util.List;

/** File-owned READY image query boundary。 */
public interface AssetImageQuery {
    List<AssetImageDescriptor> listReady(long packageId);

    List<AssetImageDescriptor> findAll(long packageId, List<Long> imageIds);

    AssetImageDescriptor require(long packageId, long imageId);

    AssetImageDescriptor require(long imageId);
}
