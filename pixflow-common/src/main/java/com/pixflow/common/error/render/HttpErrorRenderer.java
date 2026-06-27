package com.pixflow.common.error.render;

import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.common.web.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP 出口渲染器：只负责把归一化错误翻译成响应，不再定义新的错误语义。
 */
@RestControllerAdvice
public class HttpErrorRenderer {
    private final ErrorNormalizer errorNormalizer;

    public HttpErrorRenderer() {
        this(new ErrorNormalizer());
    }

    public HttpErrorRenderer(ErrorNormalizer errorNormalizer) {
        this.errorNormalizer = errorNormalizer;
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handle(Throwable throwable) {
        PixFlowException error = errorNormalizer.normalize(throwable);
        return ResponseEntity.status(error.code().httpStatus())
                .body(ApiResponse.error(error, Sanitizer.sanitizeMessage(error.getMessage())));
    }

    public ResponseEntity<ApiResponse<Void>> render(PixFlowException error) {
        return ResponseEntity.status(error.code().httpStatus())
                .body(ApiResponse.error(error, Sanitizer.sanitizeMessage(error.getMessage())));
    }
}
