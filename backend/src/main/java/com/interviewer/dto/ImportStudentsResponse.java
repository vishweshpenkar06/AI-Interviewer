package com.interviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportStudentsResponse {
    private int created;
    private int skipped;
    private List<String> errors;
    private List<StudentResponse> students;
}
