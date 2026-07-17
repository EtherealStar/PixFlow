package com.pixflow.contracts.asset;

import java.util.Objects;

/** 纯 JDK 的脱敏解析异常。 */
public final class InvalidAssetReferenceException extends IllegalArgumentException {

    private final InvalidAssetReferenceReason reason;

    public InvalidAssetReferenceException(InvalidAssetReferenceReason reason) {
        super(messageFor(reason));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public InvalidAssetReferenceReason reason() {
        return reason;
    }

    private static String messageFor(InvalidAssetReferenceReason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case NULL_OR_BLANK -> "asset reference is required";
            case INVALID_SHAPE -> "asset reference shape is invalid";
            case INVALID_IDENTIFIER -> "asset reference identifier is invalid";
            case INVALID_SKU -> "asset reference SKU is invalid";
            case NON_CANONICAL -> "asset reference is not canonical";
        };
    }
}
