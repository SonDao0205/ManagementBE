package com.backend.dto;

public record CsrfTokenResponse(String headerName, String parameterName, String token) {
}
