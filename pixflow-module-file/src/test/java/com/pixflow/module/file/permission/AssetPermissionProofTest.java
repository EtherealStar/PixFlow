package com.pixflow.module.file.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.pixflow.harness.permission.AssetAccessMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import org.junit.jupiter.api.Test;

class AssetPermissionProofTest {
    @Test
    void nonCanonicalReferenceIsDeniedBeforeReadingOwnerFacts() {
        AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);
        AssetPermissionProof proof = new AssetPermissionProof(
                new CanonicalAssetReferenceCodec(), resolver);

        assertThat(proof.proveAccess(
                new PermissionPrincipal("7", "admin"),
                "package:01/image:2",
                AssetAccessMode.PROCESS))
                .isEqualTo(ProofResult.DENIED);

        org.mockito.Mockito.verifyNoInteractions(resolver);
    }

    @Test
    void packageFactDependencyFailureIsUnavailable() {
        AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);
        when(resolver.resolve("package:1/image:2", com.pixflow.module.file.api.AssetUse.PROCESS))
                .thenThrow(new IllegalStateException("database unavailable"));
        AssetPermissionProof proof = new AssetPermissionProof(
                new CanonicalAssetReferenceCodec(), resolver);

        assertThat(proof.proveAccess(
                new PermissionPrincipal("7", "admin"),
                "package:1/image:2",
                AssetAccessMode.PROCESS))
                .isEqualTo(ProofResult.UNAVAILABLE);
    }

    @Test
    void generateRequiresProcessableBytes() {
        AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);
        when(resolver.resolve("package:1/image:2", com.pixflow.module.file.api.AssetUse.PROCESS))
                .thenReturn(mock(com.pixflow.module.file.api.ResolvedAssetReference.class));
        AssetPermissionProof proof = new AssetPermissionProof(
                new CanonicalAssetReferenceCodec(), resolver);

        assertThat(proof.proveAccess(
                new PermissionPrincipal("7", "admin"),
                "package:1/image:2",
                AssetAccessMode.GENERATE))
                .isEqualTo(ProofResult.PROVED);
        verify(resolver).resolve("package:1/image:2", com.pixflow.module.file.api.AssetUse.PROCESS);
    }
}
