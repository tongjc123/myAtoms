package com.deepwistom.atoms.session;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.common.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SessionWebConfiguration implements WebMvcConfigurer {
    private final SessionService sessions;
    private final Set<String> origins;
    public SessionWebConfiguration(SessionService sessions, @Value("${atoms.allowed-origins}") String origins) {
        this.sessions = sessions;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toSet());
        if (this.origins.isEmpty() || this.origins.contains("*")) throw new IllegalArgumentException("Explicit allowed origins are required");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("X-Request-Id", ApiExceptionHandler.requestId(request));
                response.setHeader("Cache-Control", "no-store");
                boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
                if (mutation && !origins.contains(request.getHeader("Origin"))) {
                    throw new ApiException(403, "ORIGIN_REJECTED", "请求来源不受信任，请从工作台重新操作。");
                }
                boolean upload = request.getRequestURI().equals("/api/templates/imports");
                if (!upload && request.getContentLengthLong() > 65536) {
                    throw new ApiException(413, "REQUEST_TOO_LARGE", "请求内容过大。");
                }
                if (request.getRequestURI().equals("/api/session") && request.getMethod().equals("POST")) return true;
                var owner = sessions.authenticate(request, response);
                request.setAttribute(SessionService.ATTRIBUTE, owner);
                if (mutation) sessions.checkCsrf(owner, request.getHeader("X-CSRF-Token"));
                return true;
            }
        }).addPathPatterns("/api/session", "/api/projects", "/api/projects/**",
                "/api/templates/*/clone", "/api/templates/imports", "/api/system/provider/refresh");
    }
}
