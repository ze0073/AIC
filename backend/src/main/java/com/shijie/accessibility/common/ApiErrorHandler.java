package com.shijie.accessibility.common;

import com.shijie.accessibility.ai.AiProviderException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> handleApi(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(body(ex.code(), ex.getMessage(), request, List.of()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<?> handleValidation(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", "请求参数不符合要求", request,
                List.of(ex.getMessage())));
    }

    @ExceptionHandler(AiProviderException.class)
    ResponseEntity<?> handleAiUnavailable(AiProviderException ex, HttpServletRequest request) {
        log.warn("AI provider request unavailable: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("AI_SERVICE_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试或使用手动检测", request, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "服务暂时无法处理该请求", request, List.of()));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> handleMissingResource(org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", "请求的页面或资源不存在", request, List.of()));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> handleMalformed(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请求格式或必填参数不正确", request, List.of()));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> handleContentType(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body("UNSUPPORTED_CONTENT_TYPE", "请求内容类型不受支持", request, List.of()));
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest request, Object details) {
        String traceId = (String) request.getAttribute("traceId");
        return Map.of("code", code, "message", message, "traceId", traceId == null ? "" : traceId,
                "details", details);
    }
}
