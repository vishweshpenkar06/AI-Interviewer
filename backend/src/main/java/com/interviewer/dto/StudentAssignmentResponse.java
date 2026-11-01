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
public class StudentAssignmentResponse {
    private Long assignmentId;
    private Long organizationId;
    private String organizationName;
    private Long cohortId;
    private String cohortName;
    private Long trackId;
    private String trackName;
    private String title;
    private LocalDateTime dueAt;
    private Integer maxAttempts;
    private long attemptsUsed;
    private String status;
    private Long interviewId;
    private Integer score;
    private boolean canStart;
}
