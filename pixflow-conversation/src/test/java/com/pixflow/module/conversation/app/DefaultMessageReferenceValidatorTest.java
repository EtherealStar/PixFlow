package com.pixflow.module.conversation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.asset.AssetReferenceKind;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionErrorCode;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.PermissionSource;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.file.api.ResolvedAssetReference;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultMessageReferenceValidatorTest {
    private final PermissionPolicy permissionPolicy = mock(PermissionPolicy.class);

    private final AssetReferenceResolver resolver = mock(AssetReferenceResolver.class);

    private final DefaultMessageReferenceValidator validator =
            new DefaultMessageReferenceValidator(permissionPolicy, resolver);

    private final PermissionPrincipal principal = new PermissionPrincipal("7", "admin");

    @BeforeEach
    void allowPermission() {
        when(permissionPolicy.evaluate(any(), any())).thenReturn(PermissionDecision.allow("asset"));
    }

    @Test
    void preservesOrderAndUsesBackendDisplayPath() {
        when(resolver.resolve("package:1", com.pixflow.module.file.api.AssetUse.INSPECT))
                .thenReturn(packageView("package:1", "summer.zip"));
        when(resolver.resolve("package:2", com.pixflow.module.file.api.AssetUse.INSPECT))
                .thenReturn(packageView("package:2", "winter.zip"));

        List<MessageReference> result = validator.validate(principal, "conv-1", List.of(
                new MessageReferenceInput("package:2", "winter.zip"),
                new MessageReferenceInput("package:1", "summer.zip")));

        assertThat(result).containsExactly(
                new MessageReference("package:2", "winter.zip"),
                new MessageReference("package:1", "summer.zip"));
    }

    @Test
    void rejectsDuplicateAndStaleSnapshot() {
        assertThatThrownBy(() -> validator.validate(principal, "conv-1", List.of(
                new MessageReferenceInput("package:1", "summer.zip"),
                new MessageReferenceInput("package:1", "summer.zip"))))
                .isInstanceOf(PixFlowException.class)
                .satisfies(error -> assertThat(((PixFlowException) error).code())
                        .isEqualTo(ConversationErrorCode.MESSAGE_REFERENCE_INVALID));

        when(resolver.resolve("package:1", com.pixflow.module.file.api.AssetUse.INSPECT))
                .thenReturn(packageView("package:1", "renamed.zip"));
        assertThatThrownBy(() -> validator.validate(principal, "conv-1", List.of(
                new MessageReferenceInput("package:1", "summer.zip"))))
                .isInstanceOf(PixFlowException.class);
    }

    @Test
    void preservesPermissionDenial() {
        when(permissionPolicy.evaluate(any(), any())).thenReturn(PermissionDecision.deny(
                "asset",
                PermissionSource.ASSET,
                PermissionErrorCode.PERMISSION_ASSET_DENIED));

        assertThatThrownBy(() -> validator.validate(principal, "conv-1", List.of(
                new MessageReferenceInput("package:1", "summer.zip"))))
                .isInstanceOf(PixFlowException.class)
                .satisfies(error -> assertThat(((PixFlowException) error).code())
                        .isEqualTo(PermissionErrorCode.PERMISSION_ASSET_DENIED));
    }

    private static ResolvedAssetReference packageView(String referenceKey, String displayPath) {
        return new ResolvedAssetReference(
                referenceKey,
                AssetReferenceKind.PACKAGE,
                null,
                Long.parseLong(referenceKey.substring("package:".length())),
                null,
                null,
                displayPath);
    }
}
