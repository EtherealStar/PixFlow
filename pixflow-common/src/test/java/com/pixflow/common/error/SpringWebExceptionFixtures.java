package com.pixflow.common.error;

import java.util.List;
import org.springframework.web.HttpRequestMethodNotSupportedException;

final class SpringWebExceptionFixtures {

    static HttpRequestMethodNotSupportedException methodNotAllowed(String method, String... supportedMethods) {
        return new HttpRequestMethodNotSupportedException(method, List.of(supportedMethods));
    }

    private SpringWebExceptionFixtures() {
    }
}
