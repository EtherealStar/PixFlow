package com.pixflow.harness.tools;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.permission.PermissionErrorCode;
import java.util.List;

/** 由工具执行边界注入、仅能授权当前调用发布 Proposal 的最小能力。 */
@FunctionalInterface
public interface ProposalPublicationAuthorizer {
    void authorize(String proposalType, List<String> referenceKeys, String payloadHash);

    static ProposalPublicationAuthorizer unavailable() {
        return (proposalType, referenceKeys, payloadHash) -> {
            // 缺少服务端注入的可信调用上下文时必须 fail closed，不能从工具入参补造 principal。
            throw new PixFlowException(
                    PermissionErrorCode.PERMISSION_UNAUTHENTICATED,
                    "proposal publication authorization is unavailable");
        };
    }
}
