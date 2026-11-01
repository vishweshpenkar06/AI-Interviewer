package com.interviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentResponse {
    private Long userId;
    private String fullName;
    private String email;
    private String phone;
    private String department;
    private Integer graduationYear;
    private LocalDateTime enrolledAt;
}
