package com.orchard.mall;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    public record FieldViolation(String field,String message) {}
    public record ValidationError(String code,String message,List<FieldViolation> fields) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ValidationError> validation(MethodArgumentNotValidException e) {
        var fields=e.getBindingResult().getFieldErrors().stream()
            .map(error->new FieldViolation(error.getField(),validationMessage(error.getCode(),error.getDefaultMessage())))
            .sorted(Comparator.comparing(FieldViolation::field))
            .distinct()
            .toList();
        return ResponseEntity.badRequest().body(new ValidationError(
            "VALIDATION_ERROR","请求参数校验失败",fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ValidationError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ValidationError(
            "INVALID_REQUEST_BODY","请求正文不是有效的 JSON",List.of()));
    }

    private String validationMessage(String code,String annotationMessage) {
        if(annotationMessage!=null && annotationMessage.codePoints().anyMatch(cp -> cp>=0x4e00 && cp<=0x9fff)) return annotationMessage;
        if(code==null) return "参数不合法";
        return switch(code) {
            case "NotNull","NotBlank","NotEmpty" -> "不能为空";
            case "Positive" -> "必须大于 0";
            case "PositiveOrZero" -> "不能小于 0";
            case "Size" -> "长度或数量不符合要求";
            case "Pattern" -> "格式不正确";
            default -> "参数不合法";
        };
    }

    @ExceptionHandler(OrderService.QuoteChanged.class)
    ResponseEntity<Map<String,Object>> quoteChanged(OrderService.QuoteChanged e) {
        return ResponseEntity.status(409).body(Map.of(
            "code","QUOTE_CHANGED","message","Amount or fulfillment terms changed; review again","quote",e.quote));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail response(ResponseStatusException e) {
        var detail = ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason() == null ? "Request failed" : e.getReason());
        return detail;
    }
}
