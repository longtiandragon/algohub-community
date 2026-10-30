package com.szpu.algohub.common;

/**
 * 统一响应体。失败时保留错误分类，便于前端区分凭据失效、限流与源站异常。
 */
public record ApiResult<T>(boolean success, T data, String errorKind, String message) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, data, null, null);
    }

    public static <T> ApiResult<T> ok() {
        return new ApiResult<>(true, null, null, null);
    }

    public static <T> ApiResult<T> fail(ErrorKind kind, String message) {
        return new ApiResult<>(false, null, kind.name(), message);
    }
}
