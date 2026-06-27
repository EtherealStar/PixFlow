package com.pixflow.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import org.junit.jupiter.api.Test;

class WebContractTest {

    @Test
    void okResponseWrapsData() {
        ApiResponse<String> response = ApiResponse.ok("done");
        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.data()).isEqualTo("done");
    }

    @Test
    void errorResponseUsesNormalizedError() {
        PixFlowException exception = new PixFlowException(CommonErrorCode.INVALID_PARAM, "bad", null);
        ApiResponse<Void> response = ApiResponse.error(exception, exception.getMessage());

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo(CommonErrorCode.INVALID_PARAM.code());
        assertThat(response.message()).isEqualTo("bad");
    }

    @Test
    void paginationValidatesBounds() {
        Pagination pagination = Pagination.of(2L, 20L);
        assertThat(pagination.page()).isEqualTo(2L);
        assertThat(pagination.size()).isEqualTo(20L);
    }

    @Test
    void paginationRejectsInvalidSize() {
        assertThatThrownBy(() -> Pagination.of(1L, 101L)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void pageResponseCopiesRecords() {
        PageResponse<String> response = PageResponse.of(java.util.List.of("a", "b"), 2, 1, 2);
        assertThat(response.records()).containsExactly("a", "b");
    }
}
