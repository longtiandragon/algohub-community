package com.szpu.algohub.common;

/**
 * 错误分类。区分本地校验、源站限流与登录失效，避免对所有错误一律重试。
 */
public enum ErrorKind {
    /** 请求参数或业务校验失败。 */
    VALIDATION(false),
    /** 未登录。 */
    UNAUTHENTICATED(false),
    /** 已登录但无权限。 */
    FORBIDDEN(false),
    /** 资源不存在。 */
    NOT_FOUND(false),
    /** 冲突，如重复绑定。 */
    CONFLICT(false),
    /** 源站要求登录或登录态失效。 */
    CREDENTIAL_INVALID(false),
    /** 源站限流（429 或平台自定义限制）。 */
    RATE_LIMITED(true),
    /** 源站超时或网络不可用。 */
    NETWORK(true),
    /** 源站 5xx。 */
    UPSTREAM_ERROR(true),
    /** 响应结构与预期不符，需要人工确认后再改解析逻辑。 */
    PARSE_ERROR(false),
    /** 平台暂不支持该能力。 */
    UNSUPPORTED(false),
    /** 其他未分类错误。 */
    INTERNAL(false);

    private final boolean retryable;

    ErrorKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
