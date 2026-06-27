package com.pixflow.infra.thirdparty.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

class ThirdPartyErrorMapperTest {

    private final ThirdPartyErrorMapper mapper = new ThirdPartyErrorMapper();

    @Test
    void mapsRateLimitWithRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "3");

        assertThat(mapper.fromStatus(HttpStatusCode.valueOf(429), headers, "too many", null).code().code())
                .isEqualTo("THIRDPARTY_RATE_LIMITED");
    }

    @Test
    void mapsAuthErrorsToTerminate() {
        assertThat(mapper.fromStatus(HttpStatusCode.valueOf(401), new HttpHeaders(), "unauthorized", null).recovery())
                .isEqualTo(com.pixflow.common.error.RecoveryHint.TERMINATE);
    }

    @Test
    void mapsTimeouts() {
        assertThat(mapper.map(new SocketTimeoutException("x")).code().code()).isEqualTo("THIRDPARTY_PROVIDER_TIMEOUT");
    }
}
