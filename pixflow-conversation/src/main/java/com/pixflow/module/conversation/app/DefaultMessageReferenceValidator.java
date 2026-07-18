package com.pixflow.module.conversation.app;

import com.pixflow.common.error.BusinessException;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.permission.AssetAccessMode;
import com.pixflow.harness.permission.PermissionAction;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionPlanMode;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.PermissionRuntimeScope;
import com.pixflow.harness.permission.PermissionSubject;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.file.api.AssetUse;
import com.pixflow.module.file.api.ResolvedAssetReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DefaultMessageReferenceValidator implements MessageReferenceValidator {
    public static final int MAX_REFERENCES = 20;

    private final PermissionPolicy permissionPolicy;

    private final AssetReferenceResolver referenceResolver;

    public DefaultMessageReferenceValidator(
            PermissionPolicy permissionPolicy,
            AssetReferenceResolver referenceResolver) {
        this.permissionPolicy = Objects.requireNonNull(permissionPolicy, "permissionPolicy");
        this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver");
    }

    @Override
    public List<MessageReference> validate(
            PermissionPrincipal principal,
            String conversationId,
            List<MessageReferenceInput> references) {
        Objects.requireNonNull(principal, "principal");
        List<MessageReferenceInput> inputs = references == null ? List.of() : List.copyOf(references);
        if (inputs.size() > MAX_REFERENCES) {
            throw invalid("too many message references", Map.of("maxReferences", MAX_REFERENCES));
        }

        Set<String> distinctKeys = new HashSet<>();
        List<MessageReference> validated = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            MessageReferenceInput input = inputs.get(index);
            if (input == null) {
                throw invalid("message reference must not be null", Map.of("index", index));
            }
            MessageReference submitted = submittedReference(input, index);
            if (!distinctKeys.add(submitted.referenceKey())) {
                throw invalid("duplicate message reference", Map.of("referenceKey", submitted.referenceKey()));
            }

            PermissionDecision decision;
            try {
                decision = permissionPolicy.evaluate(
                        permissionContext(principal, conversationId, index),
                        new PermissionSubject.AssetAccess(
                                submitted.referenceKey(), AssetAccessMode.INSPECT));
            } catch (IllegalArgumentException invalidReference) {
                throw invalid("message reference key is invalid", invalidReference,
                        Map.of("index", index));
            }
            if (decision.action() == PermissionAction.DENY) {
                throw new PixFlowException(decision.errorCode(), decision.reason());
            }

            ResolvedAssetReference resolved = referenceResolver.resolve(
                    submitted.referenceKey(), AssetUse.INSPECT);
            if (resolved == null
                    || !submitted.referenceKey().equals(resolved.referenceKey())
                    || !submitted.displayPathSnapshot().equals(resolved.displayPath())) {
                throw invalid("message reference snapshot is stale or invalid",
                        Map.of("index", index, "referenceKey", submitted.referenceKey()));
            }
            validated.add(new MessageReference(resolved.referenceKey(), resolved.displayPath()));
        }
        return List.copyOf(validated);
    }

    private static PermissionContext permissionContext(
            PermissionPrincipal principal, String conversationId, int index) {
        return new PermissionContext(
                principal,
                PermissionRuntimeScope.MAIN,
                PermissionPlanMode.OFF,
                conversationId,
                "message-reference:" + index);
    }

    private static MessageReference submittedReference(MessageReferenceInput input, int index) {
        try {
            return new MessageReference(input.referenceKey(), input.displayPathSnapshot());
        } catch (RuntimeException invalidReference) {
            throw invalid("message reference fields are invalid", invalidReference, Map.of("index", index));
        }
    }

    private static BusinessException invalid(String message, Map<String, ?> details) {
        return new BusinessException(ConversationErrorCode.MESSAGE_REFERENCE_INVALID, message, details);
    }

    private static BusinessException invalid(
            String message, Throwable cause, Map<String, ?> details) {
        return new BusinessException(
                ConversationErrorCode.MESSAGE_REFERENCE_INVALID, message, cause, details);
    }
}
