package com.szpu.algohub.common;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResult<Void>> handleApi(ApiException ex) {
        return ResponseEntity.status(statusOf(ex.getKind()))
                .body(ApiResult.fail(ex.getKind(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorKind.VALIDATION, message));
    }

    /**
     * 请求本身有问题（JSON 写错、缺参数、参数类型不对）是客户端错误：返回 400，
     * 不当作服务器故障打印堆栈，也不回显请求体（可能含凭据）。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResult<Void>> handleBadRequest(Exception ex) {
        String message = switch (ex) {
            case MissingServletRequestParameterException missing -> "缺少参数 " + missing.getParameterName();
            case MethodArgumentTypeMismatchException mismatch -> "参数 " + mismatch.getName() + " 的格式不对";
            default -> "请求内容不是有效的 JSON，或字段类型不对";
        };
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorKind.VALIDATION, message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResult.fail(ErrorKind.NOT_FOUND, "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResult.fail(ErrorKind.VALIDATION, "接口不支持 " + ex.getMethod() + " 请求"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResult<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResult.fail(ErrorKind.UNAUTHENTICATED, "未登录或登录已过期"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.fail(ErrorKind.FORBIDDEN, "没有访问该资源的权限"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception ex) {
        // 日志不回显请求体，避免写入凭据。
        log.error("未处理异常: {}", ex.toString(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(ErrorKind.INTERNAL, "服务器内部错误，请查看后端日志"));
    }

    private HttpStatus statusOf(ErrorKind kind) {
        return switch (kind) {
            case VALIDATION, PARSE_ERROR -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED, CREDENTIAL_INVALID -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNSUPPORTED -> HttpStatus.NOT_IMPLEMENTED;
            case NETWORK, UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
