package com.backend.controller;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.backend.service.AuthenticationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getStatus(),
                exception.getMessage());
        problem.setTitle("Authentication failed");
        problem.setType(URI.create("urn:omnichannel:problem:" + exception.getCode().toLowerCase()));
        problem.setProperty("code", exception.getCode());
        return ResponseEntity.status(exception.getStatus()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Dữ liệu đăng nhập không hợp lệ.");
        problem.setTitle("Validation failed");
        problem.setType(URI.create("urn:omnichannel:problem:validation_failed"));
        problem.setProperty("code", "VALIDATION_FAILED");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
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
}
