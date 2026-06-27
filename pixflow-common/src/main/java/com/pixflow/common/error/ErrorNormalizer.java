package com.pixflow.common.error;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 统一异常归一化入口。
 */
public class ErrorNormalizer {

    public PixFlowException normalize(Throwable throwable) {
        if (throwable instanceof PixFlowException exception) {
            return exception;
        }
        if (throwable instanceof Exception exception) {
            PixFlowException spring = normalizeSpring(exception);
            if (spring != null) {
                return spring;
            }
            PixFlowException infra = normalizeInfra(exception);
            if (infra != null) {
                return infra;
            }
        }
        return normalizeFallback(throwable);
    }

    public PixFlowException normalizeSpring(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException notValidException) {
            return invalidParam(ex, "请求参数校验失败", fieldErrors(notValidException.getFieldErrors()));
        }
        if (ex instanceof BindException bindException) {
            return invalidParam(ex, "请求参数绑定失败", fieldErrors(bindException.getFieldErrors()));
        }
        if (ex instanceof MissingServletRequestParameterException missing) {
            return invalidParam(ex, "缺少请求参数", Map.of("parameterName", missing.getParameterName(), "parameterType", missing.getParameterType()));
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return invalidParam(ex, "请求体无法解析", Map.of("reason", ex.getMessage()));
        }
        if (ex instanceof MaxUploadSizeExceededException upload) {
            return invalidParam(ex, "上传文件超过大小限制", Map.of("maxUploadSize", upload.getMaxUploadSize()));
        }
        if (ex instanceof MethodArgumentTypeMismatchException typeMismatch) {
            return invalidParam(ex, "参数类型不匹配", Map.of("name", typeMismatch.getName(), "value", String.valueOf(typeMismatch.getValue())));
        }
        if (ex instanceof TypeMismatchException typeMismatch) {
            return invalidParam(ex, "参数类型不匹配", Map.of("value", String.valueOf(typeMismatch.getValue()), "requiredType", typeMismatch.getRequiredType()));
        }
        if (ex instanceof ResponseStatusException statusException) {
            return fromHttpStatus(statusException, ex);
        }
        return null;
    }

    public PixFlowException normalizeInfra(Exception ex) {
        String simpleName = ex.getClass().getSimpleName();
        if ("NetworkException".equals(simpleName) || ex instanceof TimeoutException) {
            return new PixFlowException(
                    CommonErrorCode.NETWORK_TIMEOUT,
                    safeMessage(ex, "网络请求超时"),
                    ex,
                    Map.of("timeout", true),
                    RecoveryHint.RETRY,
                    Duration.ofSeconds(5),
                    null);
        }
        if ("RateLimitException".equals(simpleName)) {
            return new PixFlowException(
                    CommonErrorCode.RATE_LIMITED,
                    safeMessage(ex, "触发限流"),
                    ex,
                    Map.of(),
                    RecoveryHint.RETRY,
                    Duration.ofSeconds(1),
                    null);
        }
        if ("ProviderException".equals(simpleName)) {
            return new PixFlowException(CommonErrorCode.PROVIDER_FAILURE, safeMessage(ex, "供应商返回异常"), ex);
        }
        if ("StorageException".equals(simpleName)) {
            return new PixFlowException(CommonErrorCode.STORAGE_FAILURE, safeMessage(ex, "存储操作失败"), ex);
        }
        if ("CacheException".equals(simpleName)) {
            return new PixFlowException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, safeMessage(ex, "缓存服务不可用"), ex);
        }
        if ("ImageProcessingException".equals(simpleName)) {
            return new PixFlowException(CommonErrorCode.IMAGE_PROCESSING_FAILURE, safeMessage(ex, "图片处理失败"), ex);
        }
        if ("ToolException".equals(simpleName)) {
            return new PixFlowException(CommonErrorCode.TOOL_FAILURE, safeMessage(ex, "工具执行失败"), ex);
        }
        if ("DependencyUnavailableException".equals(simpleName)) {
            return new PixFlowException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, safeMessage(ex, "依赖服务不可用"), ex);
        }
        if ("ContextLimitExceededException".equals(simpleName)) {
            return new PixFlowException(CommonErrorCode.CONTEXT_LIMIT_EXCEEDED, safeMessage(ex, "上下文超出限制"), ex);
        }
        return null;
    }

    public PixFlowException normalizeFallback(Throwable throwable) {
        return new PixFlowException(
                CommonErrorCode.INTERNAL_ERROR,
                safeMessage(throwable, "系统内部错误"),
                throwable,
                Map.of("exceptionClass", throwable.getClass().getName()));
    }

    private PixFlowException invalidParam(Throwable cause, String message, Map<String, Object> details) {
        return new PixFlowException(CommonErrorCode.INVALID_PARAM, message, cause, details);
    }

    private PixFlowException fromHttpStatus(ResponseStatusException exception, Exception cause) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == HttpStatus.NOT_FOUND) {
            return new PixFlowException(CommonErrorCode.RESOURCE_NOT_FOUND, safeMessage(exception, "资源不存在"), cause);
        }
        if (status == HttpStatus.FORBIDDEN) {
            return new PixFlowException(CommonErrorCode.PERMISSION_DENIED, safeMessage(exception, "无权限访问"), cause);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return new PixFlowException(CommonErrorCode.RATE_LIMITED, safeMessage(exception, "请求过于频繁"), cause);
        }
        if (status == HttpStatus.BAD_REQUEST) {
            return new PixFlowException(CommonErrorCode.INVALID_PARAM, safeMessage(exception, "请求参数错误"), cause);
        }
        return new PixFlowException(CommonErrorCode.INTERNAL_ERROR, safeMessage(exception, "请求失败"), cause);
    }

    private Map<String, Object> fieldErrors(List<FieldError> fieldErrors) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fieldErrors", fieldErrors.stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "rejectedValue", String.valueOf(error.getRejectedValue()),
                        "message", error.getDefaultMessage()))
                .toList());
        return details;
    }

    private String safeMessage(Throwable throwable, String fallback) {
        String message = throwable.getMessage();
        return StringUtils.hasText(message) ? message : fallback;
    }
}
