package com.pixflow.module.file.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pixflow.harness.permission.AssetAccessMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import org.junit.jupiter.api.Test;

class AssetPermissionProofTest {
    @Test
    void nonCanonicalReferenceIsDeniedBeforeReadingOwnerFacts() {
        AssetPackageService packages = mock(AssetPackageService.class);
        AssetImageMapper images = mock(AssetImageMapper.class);
        AssetPermissionProof proof = new AssetPermissionProof(packages, images);

        assertThat(proof.proveAccess(
                new PermissionPrincipal("7", "admin"),
                "package:01/image:2",
                AssetAccessMode.PROCESS))
                .isEqualTo(ProofResult.DENIED);

        verifyNoInteractions(packages, images);
    }

    @Test
    void packageFactDependencyFailureIsUnavailable() {
        AssetPackageService packages = mock(AssetPackageService.class);
        when(packages.require(1L)).thenThrow(new IllegalStateException("database unavailable"));
        AssetPermissionProof proof = new AssetPermissionProof(
                packages, mock(AssetImageMapper.class));

        assertThat(proof.proveAccess(
                new PermissionPrincipal("7", "admin"),
                "package:1/image:2",
                AssetAccessMode.PROCESS))
                .isEqualTo(ProofResult.UNAVAILABLE);
    }
}
