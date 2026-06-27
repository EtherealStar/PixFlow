package com.pixflow.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import java.lang.reflect.Method;

class ErrorNormalizerTest {

    private final ErrorNormalizer normalizer = new ErrorNormalizer();

    @Test
    void normalizesKnownRuntimeToInternal() {
        PixFlowException exception = normalizer.normalize(new RuntimeException("boom"));
        assertThat(exception.code()).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
    }

    @Test
    void normalizesValidationFailureToInvalidParam() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "payload");
        bindingResult.addError(new FieldError("payload", "name", "name is required"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(dummyMethodParameter(), bindingResult);

        PixFlowException exception = normalizer.normalize(ex);

        assertThat(exception.code()).isEqualTo(CommonErrorCode.INVALID_PARAM);
        assertThat(exception.details()).containsKey("fieldErrors");
    }

    @Test
    void normalizesTimeoutToNetwork() {
        PixFlowException exception = normalizer.normalize(new java.util.concurrent.TimeoutException("slow"));
        assertThat(exception.code()).isEqualTo(CommonErrorCode.NETWORK_TIMEOUT);
        assertThat(exception.retryAfter()).isEqualTo(java.time.Duration.ofSeconds(5));
    }

    private MethodParameter dummyMethodParameter() {
        try {
            Method method = ErrorNormalizerTest.class.getDeclaredMethod("sample", String.class);
            return new MethodParameter(method, 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private void sample(String value) {
    }
}
