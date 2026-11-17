package com.deepwistom.atoms.common;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean retryable;

    public ApiException(int status, String code, String message) {
        this(status, code, message, false);
    }

    public ApiException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public int status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }

    public static ApiException invalid(String message) {
        return new ApiException(400, "VALIDATION_ERROR", message);
    }
}
