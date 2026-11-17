package com.deepwistom.atoms.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 65536;
    private final ObjectMapper json;
    public RequestBodyLimitFilter(ObjectMapper json) { this.json = json; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean protectedPath = path.equals("/api/session") || path.equals("/api/projects") || path.startsWith("/api/projects/");
        return !protectedPath || Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            reject(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            reject(request, response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
            @Override public ServletInputStream getInputStream() {
                var source = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return source.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) { return source.read(bytes, offset, length); }
                    @Override public boolean isFinished() { return source.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new IllegalStateException("Non-blocking reads are not used by bounded JSON endpoints");
                    }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestId = ApiExceptionHandler.requestId(request);
        response.setStatus(413);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-Id", requestId);
        json.writeValue(response.getOutputStream(), new ApiExceptionHandler.ErrorBody(
                "REQUEST_TOO_LARGE", "请求内容不能超过 64KiB。", false, requestId));
    }
}
