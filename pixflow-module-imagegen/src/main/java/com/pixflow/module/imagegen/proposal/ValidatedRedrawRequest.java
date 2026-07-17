package com.pixflow.module.imagegen.proposal;

import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import java.util.Objects;

/** Imagegen owner 深校验后交给 App 编排层的单图 redraw 请求。 */
public record ValidatedRedrawRequest(
        ImageAssetReferenceKey source,
        String canonicalPayload,
        String payloadHash) {

    public ValidatedRedrawRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        Objects.requireNonNull(payloadHash, "payloadHash");
    }
}
