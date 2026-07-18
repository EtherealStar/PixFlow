package com.pixflow.module.conversation.app;

import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.permission.PermissionPrincipal;
import java.util.List;

public interface MessageReferenceValidator {
    List<MessageReference> validate(
            PermissionPrincipal principal,
            String conversationId,
            List<MessageReferenceInput> references);
}
