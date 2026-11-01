package com.interviewer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCohortRequest {
    @NotBlank
    @Size(max = 150)
    private String name;

    @Size(max = 120)
    private String department;

    @Min(2000)
    @Max(2100)
    private Integer graduationYear;
}
