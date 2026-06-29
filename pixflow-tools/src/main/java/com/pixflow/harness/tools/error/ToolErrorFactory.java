package com.pixflow.harness.tools.error;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import java.util.Map;

public final class ToolErrorFactory {
    private ToolErrorFactory() {
    }

    public static PixFlowException validation(String message, Map<String, Object> details) {
        return new PixFlowException(CommonErrorCode.INVALID_PARAM, message, null, details);
    }

    public static PixFlowException permission(String message, Map<String, Object> details) {
        return new PixFlowException(CommonErrorCode.PERMISSION_DENIED, message, null, details);
    }

    public static PixFlowException tool(String message, Map<String, Object> details) {
        return new PixFlowException(CommonErrorCode.TOOL_FAILURE, message, null, details, RecoveryHint.SKIP, null, null);
    }

    public static PixFlowException internal(String message, Map<String, Object> details) {
        return new PixFlowException(CommonErrorCode.INTERNAL_ERROR, message, null, details);
    }
}
