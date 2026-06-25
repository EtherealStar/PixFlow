package com.etherealstar.pixflow.common.error;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ErrorResponseTest {

    @Test
    void ofErrorCodeUsesEnumNameAndDefaultMessage() {
        ErrorResponse response = ErrorResponse.of(ErrorCode.ASSET_ZIP_INVALID);

        assertEquals("ASSET_ZIP_INVALID", response.getCode());
        assertEquals(ErrorCode.ASSET_ZIP_INVALID.getDefaultMessage(), response.getMessage());
        assertNull(response.getDetails());
    }

    @Test
    void ofWithCustomMessageOverridesDefault() {
        ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_PAGINATION, "page 必须 >= 1");

        assertEquals("INVALID_PAGINATION", response.getCode());
        assertEquals("page 必须 >= 1", response.getMessage());
    }

    @Test
    void emptyDetailsAreNormalizedToNull() {
        ErrorResponse response = ErrorResponse.of(
                ErrorCode.DAG_PARAM_INVALID, "msg", new LinkedHashMap<>());

        assertNull(response.getDetails());
    }

    @Test
    void nonEmptyDetailsArePreserved() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nodeId", "n5");
        ErrorResponse response = ErrorResponse.of(ErrorCode.DAG_PARAM_INVALID, "msg", details);

        assertEquals("n5", response.getDetails().get("nodeId"));
    }

    @Test
    void everyErrorCodeHasMessageAndStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(false, code.getDefaultMessage() == null || code.getDefaultMessage().isBlank(),
                    code.name() + " should have a non-blank default message");
            assertSame(code.getHttpStatus(), code.getHttpStatus());
        }
    }
}
