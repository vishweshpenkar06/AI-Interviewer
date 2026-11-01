package com.interviewer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateAssignmentRequest {
    @NotNull
    private Long cohortId;

    private Long trackId;

    @NotBlank
    @Size(max = 200)
    private String title;

    private LocalDateTime dueAt;

    @Min(1)
    @Max(20)
    private Integer maxAttempts;
}
