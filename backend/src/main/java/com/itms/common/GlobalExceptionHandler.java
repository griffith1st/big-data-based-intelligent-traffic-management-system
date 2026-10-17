package com.itms.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleStatus(ResponseStatusException e) {
        var response = ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders());
        if ((e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503)
                && !e.getHeaders().containsKey("Retry-After")) {
            response.header("Retry-After", "1");
        }
        return response.body(Result.error(e.getStatusCode().value(), e.getReason()));
    }

    @ExceptionHandler({QueryTimeoutException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class, CannotAcquireLockException.class})
    public ResponseEntity<Result<Void>> handleDatabaseCapacity(RuntimeException e) {
        log.warn("Database temporarily unavailable: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(Result.error(503, "数据库繁忙，请稍后重试"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConflict(DataIntegrityViolationException e) {
        log.warn("Database constraint rejected a request: {}", e.getClass().getSimpleName());
        return Result.error(409, "数据冲突，请检查重复记录或当前状态");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleInvalidRequest(Exception e) {
        return Result.error(400, "请求参数不合法，请检查必填项、格式和取值范围");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.error("参数异常: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        if (e instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(Result.error(status, "请求未被处理，请检查接口地址和请求方式"));
        }
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统内部错误"));
    }
}
