package com.pixflow.infra.auth.error;

import com.pixflow.common.error.PixFlowException;
import java.time.Duration;
import java.util.Map;

public class AuthException extends PixFlowException {
    public AuthException(AuthErrorCode code, String message) {
        super(code, message);
    }

    public AuthException(AuthErrorCode code, String message, Map<String, ?> details) {
        super(code, message, null, details);
    }

    public AuthException(AuthErrorCode code, String message, Duration retryAfter) {
        super(code, message, null, Map.of(), null, retryAfter, null);
    }
}
