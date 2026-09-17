package com.monitor.common.exception;

import com.monitor.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理：统一转换为 {@link Result} 响应。
 */
@Slf4j
@RestControllerAdvice
@SuppressWarnings("null")
public class GlobalExceptionHandler {

    /** 业务异常：HTTP 状态与 body.code 一致（如 404 测点不存在 → HTTP 404）。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.status(httpStatusOf(e.getCode()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = firstFieldError(e.getBindingResult().getFieldError());
        return Result.fail(400, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBind(BindException e) {
        String msg = firstFieldError(e.getBindingResult().getFieldError());
        return Result.fail(400, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.fail(403, "无权限访问");
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuth(AuthenticationException e) {
        return Result.fail(401, "用户名或密码错误");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        return Result.fail(404, "接口不存在: " + e.getResourcePath());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(Exception e) {
        return Result.fail(400, "请求参数格式错误");
    }

    /**
     * 路径存在、但不是这个 HTTP 方法（如对 {@code /api/v1/users} 发 POST——那里只有 GET）。
     *
     * <p>不接这一条的话它会落到下面的通用兜底里变成 500「系统内部错误」，
     * 而调用方看到的是「服务端崩了」而不是「你用错方法了」——2026-09-17 实测到过：
     * 用户管理去掉建号端点后，对 POST /users 的断言拿到的是 500。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return Result.fail(405, "该接口不支持 " + e.getMethod() + " 方法");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "系统内部错误"));
    }

    /** body.code 走 HTTP 语义码；遇到非标准码按客户端错误处理。 */
    private static HttpStatus httpStatusOf(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.BAD_REQUEST;
    }

    private String firstFieldError(FieldError fe) {
        return fe != null ? fe.getDefaultMessage() : "参数校验失败";
    }
}
