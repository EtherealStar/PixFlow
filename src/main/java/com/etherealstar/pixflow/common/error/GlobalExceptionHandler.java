package com.etherealstar.pixflow.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器。
 *
 * <p>将业务异常与常见框架异常统一转换为 {@link ErrorResponse} 结构 {@code {code, message, details}}，
 * 并设置对应的 HTTP 状态码（design.md「Error Handling」）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.error("Business error [{}]: {}", errorCode.name(), ex.getMessage(), ex);
        } else {
            log.warn("Business error [{}]: {}", errorCode.name(), ex.getMessage());
        }
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ex.toResponse());
    }

    /** 上传体积超过 Spring 的 multipart 限制时映射为 zip 体积超限错误。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        ErrorCode errorCode = ErrorCode.ASSET_ZIP_TOO_LARGE;
        log.warn("Upload size exceeded: {}", ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ErrorResponse.of(errorCode));
    }

    /** 缺少必填请求参数（如缺失 zip_file 字段）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", ex.getParameterName());
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.MESSAGE_CONTENT_INVALID, "缺少必填参数: " + ex.getParameterName(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Bean Validation 失败（@Valid 校验）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                details.put(fieldError.getField(), fieldError.getDefaultMessage()));
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.MESSAGE_CONTENT_INVALID, "请求参数校验失败", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 非法参数（IllegalArgumentException）兜底为请求非法。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.of(ErrorCode.MESSAGE_CONTENT_INVALID, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 兜底：未预期的服务端错误。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ErrorResponse.of(errorCode));
    }
}
