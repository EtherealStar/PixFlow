package com.pixflow.infra.auth.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {
    AUTH_USERNAME_TAKEN("AUTH_USERNAME_TAKEN", ErrorCategory.BUSINESS_RULE, HttpStatus.CONFLICT),
    AUTH_INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", ErrorCategory.PERMISSION, HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_MISSING("AUTH_TOKEN_MISSING", ErrorCategory.PERMISSION, HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_INVALID("AUTH_TOKEN_INVALID", ErrorCategory.PERMISSION, HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED("AUTH_TOKEN_EXPIRED", ErrorCategory.PERMISSION, HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_REVOKED("AUTH_TOKEN_REVOKED", ErrorCategory.PERMISSION, HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_EXPIRED("AUTH_REFRESH_EXPIRED", ErrorCategory.PERMISSION, HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_INVALID("AUTH_REFRESH_INVALID", ErrorCategory.PERMISSION, HttpStatus.UNAUTHORIZED),
    AUTH_ACCOUNT_DISABLED("AUTH_ACCOUNT_DISABLED", ErrorCategory.PERMISSION, HttpStatus.FORBIDDEN),
    AUTH_TOO_MANY_ATTEMPTS("AUTH_TOO_MANY_ATTEMPTS", ErrorCategory.RATE_LIMIT, HttpStatus.TOO_MANY_REQUESTS),
    AUTH_USERNAME_INVALID("AUTH_USERNAME_INVALID", ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST),
    AUTH_PASSWORD_INVALID("AUTH_PASSWORD_INVALID", ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST);

    private final String code;
    private final ErrorCategory category;
    private final HttpStatus httpStatus;

    AuthErrorCode(String code, ErrorCategory category, HttpStatus httpStatus) {
        this.code = code;
        this.category = category;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
