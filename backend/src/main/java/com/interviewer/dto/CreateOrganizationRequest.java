package com.interviewer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateOrganizationRequest {
    @NotBlank
    @Size(max = 150)
    private String name;

    @Size(max = 80)
    private String slug;

    private String plan;
}
