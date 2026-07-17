package com.pixflow.infra.auth.filter;

import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 不存在的路由保持 404；只有真实受保护端点才返回认证错误。 */
public final class RouteAwareAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final RequestMappingHandlerMapping handlerMappings;

    private final SecurityErrorWriter errorWriter;

    public RouteAwareAuthenticationEntryPoint(
            RequestMappingHandlerMapping handlerMappings, SecurityErrorWriter errorWriter) {
        this.handlerMappings = handlerMappings;
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        try {
            // 只查询 Controller mapping，避免静态资源的 /** 兜底规则把未知 API 误判为受保护端点。
            if (handlerMappings.getHandler(request) == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        } catch (Exception ignored) {
            // 无法可靠判断 mapping 时按认证失败关闭，不能把真实受保护端点降级为匿名访问。
        }
        errorWriter.write(response, new AuthException(AuthErrorCode.AUTH_TOKEN_MISSING, "需要登录后访问"));
    }
}
