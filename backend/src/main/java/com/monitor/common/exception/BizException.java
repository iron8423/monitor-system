package com.monitor.common.exception;

import lombok.Getter;

/**
 * 业务异常，由 {@link GlobalExceptionHandler} 统一转换为 {@code Result}。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
