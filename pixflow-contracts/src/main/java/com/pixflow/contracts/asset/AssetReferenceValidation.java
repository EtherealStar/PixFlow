package com.pixflow.contracts.asset;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class AssetReferenceValidation {

    private AssetReferenceValidation() {
    }

    static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    static void requireSkuId(String skuId) {
        if (skuId == null || skuId.isEmpty() || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must contain a non-whitespace character");
        }
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(skuId));
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException("skuId must be valid Unicode", invalidUnicode);
        }
    }
}
