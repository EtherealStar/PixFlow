package com.pixflow.module.file.permission;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.harness.permission.AssetAccessMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.file.api.AssetUse;

/** 由 File 当前数据库事实证明 canonical Asset Reference 可用于指定动作。 */
public final class AssetPermissionProof implements AssetAuthorizationPort {
    private final AssetReferenceResolver referenceResolver;

    private final CanonicalAssetReferenceCodec codec;

    public AssetPermissionProof(
            CanonicalAssetReferenceCodec codec, AssetReferenceResolver referenceResolver) {
        this.codec = codec;
        this.referenceResolver = referenceResolver;
    }

    @Override
    public ProofResult proveAccess(
            PermissionPrincipal principal, String referenceKey, AssetAccessMode mode) {
        if (principal == null || mode == null) {
            return ProofResult.DENIED;
        }
        try {
            String canonicalKey = codec.serialize(codec.parse(referenceKey));
            if (referenceResolver.resolve(canonicalKey, toAssetUse(mode)) == null) {
                return ProofResult.DENIED;
            }
            return ProofResult.PROVED;
        } catch (PixFlowException | IllegalArgumentException denied) {
            return ProofResult.DENIED;
        } catch (RuntimeException unavailable) {
            return ProofResult.UNAVAILABLE;
        }
    }

    private static AssetUse toAssetUse(AssetAccessMode mode) {
        return switch (mode) {
            case READ -> AssetUse.DOWNLOAD;
            case INSPECT -> AssetUse.INSPECT;
            case PROCESS, GENERATE -> AssetUse.PROCESS;
        };
    }
}
