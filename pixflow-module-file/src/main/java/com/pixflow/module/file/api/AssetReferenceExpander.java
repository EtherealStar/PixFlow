package com.pixflow.module.file.api;

import java.util.List;

public interface AssetReferenceExpander {
    ExpandedAssetSet expand(List<String> referenceKeys, AssetUse use);

}
