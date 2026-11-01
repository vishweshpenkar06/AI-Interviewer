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
public class AssignmentResponse {
    private Long id;
    private Long cohortId;
    private String cohortName;
    private Long trackId;
    private String trackName;
    private String title;
    private LocalDateTime dueAt;
    private Integer maxAttempts;
    private LocalDateTime createdAt;
    private long attemptCount;
    private long completedCount;
}
