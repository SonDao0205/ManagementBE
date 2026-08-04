package com.backend.controller;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.backend.service.ApiException;
import com.backend.service.AuthenticationException;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException exception) {
        return problem(exception.getStatus(), exception.getCode(), exception.getMessage(), "Request failed");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception) {
        return problem(exception.getStatus(), exception.getCode(), exception.getMessage(),
                "Authentication failed");
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    ResponseEntity<ProblemDetail> handleAuthorizationDenied(
            AuthorizationDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "Bạn không có quyền thực hiện thao tác này.", "Forbidden");
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ProblemDetail> handleConstraintValidation(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Tham số yêu cầu không hợp lệ.", "Validation failed");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Nội dung yêu cầu không đúng định dạng JSON hoặc sai kiểu dữ liệu.",
                "Invalid request body");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return problem(HttpStatus.valueOf(413), "MEDIA_REQUEST_TOO_LARGE",
                "Mỗi lượt tải ảnh hoặc video được tối đa 200MB.",
                "Media request too large");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        String detail = exception.getReason() == null ? "Yêu cầu không thể xử lý." : exception.getReason();
        return problem(resolved, "REQUEST_REJECTED", detail, "Request rejected");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, "DATA_WAS_CHANGED",
                "Dữ liệu vừa được người khác cập nhật. Vui lòng tải lại và thử lại.",
                "Concurrent update conflict");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Dữ liệu yêu cầu không hợp lệ.");
        problem.setTitle("Validation failed");
        problem.setType(URI.create("urn:omnichannel:problem:validation_failed"));
        problem.setProperty("code", "VALIDATION_FAILED");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().stream()
                .sorted((left, right) -> Integer.compare(
                        validationPriority(left.getCode()),
                        validationPriority(right.getCode())))
                .forEach(error ->
                        fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    private int validationPriority(String validationCode) {
        return switch (validationCode == null ? "" : validationCode) {
            case "NotBlank", "NotNull" -> 0;
            case "Size" -> 1;
            case "Email", "Pattern" -> 2;
            default -> 3;
        };
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(
            DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        boolean marketplaceOwnershipConflict = message != null
                && message.toLowerCase(Locale.ROOT)
                        .contains("uq_marketplace_accounts_external_owner");
        String code = marketplaceOwnershipConflict
                ? "SHOP_ALREADY_CONNECTED_TO_ANOTHER_TENANT"
                : "DATA_INTEGRITY_CONFLICT";
        String detail = marketplaceOwnershipConflict
                ? "Shop này đã được liên kết với một doanh nghiệp khác."
                : "Dữ liệu yêu cầu xung đột với dữ liệu hiện có.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                detail);
        problem.setTitle("Data conflict");
        problem.setType(URI.create(
                "urn:omnichannel:problem:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        LOGGER.error("Unexpected API error", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Hệ thống chưa thể xử lý yêu cầu. Vui lòng thử lại sau.",
                "Internal server error");
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String code,
            String detail,
            String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:omnichannel:problem:" + code.toLowerCase(Locale.ROOT)));
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
