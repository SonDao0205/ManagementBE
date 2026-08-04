package com.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiFeedbackRequest(
        @NotBlank @Size(max = 36) String aiResponseRunId,
        @Min(1) @Max(5) int rating,
        @NotBlank @Pattern(regexp = "GOOD|INCORRECT|UNNATURAL|MISSING_CONTEXT|POLICY|TONE|HALLUCINATION|OTHER")
        String feedbackType,
        @Size(max = 4000) String commentText,
        @Size(max = 8000) String correctedText) {
}
