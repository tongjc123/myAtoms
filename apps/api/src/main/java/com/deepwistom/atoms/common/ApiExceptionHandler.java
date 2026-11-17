package com.deepwistom.atoms.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ErrorBody(String code, String message, boolean retryable, String requestId) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> domain(ApiException error, HttpServletRequest request) {
        return error(error.status(), error.code(), error.getMessage(), error.retryable(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalid(Exception ignored, HttpServletRequest request) {
        return error(400, "VALIDATION_ERROR", "请求格式不正确，请检查填写内容。", false, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> media(Exception ignored, HttpServletRequest request) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "请求必须使用 JSON 格式。", false, request);
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionSystemException.class})
    ResponseEntity<ErrorBody> storage(RuntimeException error, HttpServletRequest request) {
        log.error("Storage unavailable requestId={} exception={}", requestId(request), error.getClass().getSimpleName());
        return error(503, "STORAGE_UNAVAILABLE", "暂时无法保存或读取数据，请稍后重试。", true, request);
    }

    private ResponseEntity<ErrorBody> error(int status, String code, String message,
                                           boolean retryable, HttpServletRequest request) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorBody(code, message, retryable, requestId(request)));
    }

    public static String requestId(HttpServletRequest request) {
        Object current = request.getAttribute("atoms.requestId");
        if (current instanceof String id) return id;
        String id = UUID.randomUUID().toString();
        request.setAttribute("atoms.requestId", id);
        return id;
    }
}
