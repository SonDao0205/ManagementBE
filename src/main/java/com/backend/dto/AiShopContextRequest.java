package com.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiShopContextRequest(
        @NotBlank(message = "Vui lòng chọn shop.")
        @Size(max = 36, message = "Mã shop không hợp lệ.")
        String marketplaceAccountId,

        @NotBlank(message = "Vui lòng nhập tên ngữ cảnh.")
        @Size(max = 150, message = "Tên ngữ cảnh tối đa 150 ký tự.")
        String contextName,

        @NotBlank(message = "Vui lòng chọn mood.")
        @Pattern(
                regexp = "PROFESSIONAL|FRIENDLY|WARM|YOUTHFUL|CONCISE|EMPATHETIC|CUSTOM",
                message = "Mood không hợp lệ.")
        String mood,

        @NotBlank(message = "Vui lòng nhập tên trợ lý.")
        @Size(max = 100, message = "Tên trợ lý tối đa 100 ký tự.")
        String assistantName,

        @Size(max = 4000, message = "Mô tả shop tối đa 4000 ký tự.")
        String businessDescription,

        @NotBlank(message = "Vui lòng nhập giọng điệu thương hiệu.")
        @Size(max = 1000, message = "Giọng điệu thương hiệu tối đa 1000 ký tự.")
        String brandVoice,

        @Size(max = 8000, message = "Hướng dẫn trả lời tối đa 8000 ký tự.")
        String responseGuidelines,

        @Size(max = 30, message = "Chỉ được cấu hình tối đa 30 chủ đề cấm.")
        List<@NotBlank(message = "Chủ đề cấm không được để trống.")
                @Size(max = 200, message = "Mỗi chủ đề cấm tối đa 200 ký tự.") String>
                prohibitedTopics,

        @NotBlank(message = "Vui lòng nhập ngôn ngữ mặc định.")
        @Pattern(
                regexp = "^[A-Za-z]{2,3}([-_][A-Za-z]{2,4})?$",
                message = "Mã ngôn ngữ không hợp lệ, ví dụ: vi hoặc vi-VN.")
        String defaultLanguage,

        @Min(value = 100, message = "Độ dài trả lời tối thiểu là 100 ký tự.")
        @Max(value = 8000, message = "Độ dài trả lời tối đa là 8000 ký tự.")
        Integer maxResponseCharacters,

        @Size(max = 36, message = "Mã kho tri thức không hợp lệ.")
        String defaultKnowledgeBaseId) {
}
