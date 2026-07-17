package com.pixflow.contracts.asset;

/** 可稳定判定、且不回显不可信 referenceKey 的失败原因。 */
public enum InvalidAssetReferenceReason {
    NULL_OR_BLANK,
    INVALID_SHAPE,
    INVALID_IDENTIFIER,
    INVALID_SKU,
    NON_CANONICAL
}
