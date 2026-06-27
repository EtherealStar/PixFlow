package com.pixflow.infra.ai.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.PixFlowException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class ProviderErrorMapperTest {

    @Test
    void mapsRateLimitWithRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "3");

        PixFlowException exception = ProviderErrorMapper.fromHttpStatus(HttpStatus.TOO_MANY_REQUESTS, new RuntimeException("x"), headers, "rate limited");

        assertThat(exception.code()).isEqualTo(AiErrorCode.MODEL_RATE_LIMITED);
        assertThat(exception.category()).isEqualTo(ErrorCategory.RATE_LIMIT);
        assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void mapsContextLimitAndAuth() {
        PixFlowException context = ProviderErrorMapper.fromHttpStatus(HttpStatus.PAYLOAD_TOO_LARGE, null, null, "context limit exceeded");
        PixFlowException auth = ProviderErrorMapper.fromHttpStatus(HttpStatus.FORBIDDEN, null, null, "api key invalid");

        assertThat(context.code()).isEqualTo(AiErrorCode.MODEL_CONTEXT_LIMIT);
        assertThat(auth.code()).isEqualTo(AiErrorCode.MODEL_AUTH_ERROR);
    }
}
