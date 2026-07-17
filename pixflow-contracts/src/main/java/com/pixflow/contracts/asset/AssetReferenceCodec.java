package com.pixflow.contracts.asset;

/** Asset Reference 的唯一解析与序列化合同。 */
public interface AssetReferenceCodec {

    AssetReferenceKey parse(String referenceKey);

    String serialize(AssetReferenceKey reference);
}
