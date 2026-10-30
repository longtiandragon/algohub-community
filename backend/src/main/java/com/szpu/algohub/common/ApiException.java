package com.szpu.algohub.common;

public class ApiException extends RuntimeException {

    private final ErrorKind kind;

    public ApiException(ErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ApiException(ErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public ErrorKind getKind() {
        return kind;
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorKind.VALIDATION, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorKind.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorKind.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorKind.FORBIDDEN, message);
    }
}
