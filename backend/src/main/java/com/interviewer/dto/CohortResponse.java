package com.interviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CohortResponse {
    private Long id;
    private String name;
    private String department;
    private Integer graduationYear;
    private long studentCount;
    private long assignmentCount;
}
