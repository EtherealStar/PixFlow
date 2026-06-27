package com.pixflow.infra.mq.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryHeadersTest {

    @Test
    void readsAndIncrementsRetryCount() {
        Map<String, Object> headers = RetryHeaders.incrementRetry(Map.of(RetryHeaders.RETRY_COUNT, "1"));

        assertThat(RetryHeaders.retryCount(headers)).isEqualTo(2);
        assertThat(headers).containsKey(RetryHeaders.FIRST_FAILURE_AT);
    }

    @Test
    void storesOriginalRouteAndSanitizedFailure() {
        PixFlowException error = new PixFlowException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, "failed at D:\\secret\\token.txt");

        Map<String, Object> headers = RetryHeaders.withOriginalRoute(
                RetryHeaders.withFailure(Map.of(), error),
                "pixflow.test",
                "test.submit");

        assertThat(headers).containsEntry(RetryHeaders.ORIGINAL_EXCHANGE, "pixflow.test");
        assertThat(headers).containsEntry(RetryHeaders.ORIGINAL_ROUTING_KEY, "test.submit");
        assertThat(headers).containsEntry(RetryHeaders.LAST_ERROR_CODE, "DEPENDENCY_UNAVAILABLE");
        assertThat((String) headers.get(RetryHeaders.LAST_ERROR_MESSAGE)).contains("<external>");
    }
}
