package com.etherealstar.pixflow.common.error;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessExceptionTest {

    @Test
    void defaultsToErrorCodeMessage() {
        BusinessException ex = new BusinessException(ErrorCode.PACKAGE_NOT_FOUND);

        assertEquals(ErrorCode.PACKAGE_NOT_FOUND, ex.getErrorCode());
        assertEquals(ErrorCode.PACKAGE_NOT_FOUND.getDefaultMessage(), ex.getMessage());
    }

    @Test
    void customMessageIsUsed() {
        BusinessException ex = new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务 42 不存在");

        assertEquals("任务 42 不存在", ex.getMessage());
    }

    @Test
    void toResponseCarriesCodeMessageAndDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("limit", 500);
        BusinessException ex = new BusinessException(
                ErrorCode.ASSET_ZIP_TOO_LARGE, "超过 500 MB", details);

        ErrorResponse response = ex.toResponse();

        assertEquals("ASSET_ZIP_TOO_LARGE", response.getCode());
        assertEquals("超过 500 MB", response.getMessage());
        assertEquals(500, response.getDetails().get("limit"));
    }
}
