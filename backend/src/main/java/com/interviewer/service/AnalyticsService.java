package com.interviewer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.entity.*;
import com.interviewer.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final CohortRepository cohortRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentAttemptRepository attemptRepository;
    private final InterviewRepository interviewRepository;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    private static final List<String> CRITERIA =
            List.of("relevance", "technicalDepth", "correctness", "clarity", "evidence");

    @Transactional(readOnly = true)
    public Map<String, Object> forOrganization(Long organizationId) {
        tenantContext.requireMembership(organizationId);

        List<Cohort> cohorts = cohortRepository.findByOrganizationIdOrderByIdDesc(organizationId);
        List<Assignment> assignments =
                assignmentRepository.findByCohortOrganizationIdOrderByCreatedAtDesc(organizationId);
        List<AssignmentAttempt> attempts =
                attemptRepository.findByAssignmentCohortOrganizationId(organizationId);

        // Completion is per student assignment, not per attempt: a second
        // successful attempt must not push completion above 100%.
        long completed = attempts.stream()
                .filter(a -> a.getStatus() == AssignmentAttempt.Status.COMPLETED)
                .map(a -> a.getAssignment().getId() + ":" + a.getStudent().getId())
                .distinct()
                .count();

        long expected = 0;
        for (Cohort cohort : cohorts) {
            long cohortAssignments = assignments.stream()
                    .filter(a -> a.getCohort().getId().equals(cohort.getId())).count();
            expected += cohortAssignments * enrollmentRepository.countByCohortId(cohort.getId());
        }

        List<Map<String, Object>> cohortRows = new ArrayList<>();
        for (Cohort cohort : cohorts) {
            long students = enrollmentRepository.countByCohortId(cohort.getId());
            long assigned = assignments.stream()
                    .filter(a -> a.getCohort().getId().equals(cohort.getId())).count();
            long cohortAttempts = attempts.stream()
                    .filter(a -> a.getAssignment().getCohort().getId().equals(cohort.getId())).count();
            long cohortCompleted = attempts.stream()
                    .filter(a -> a.getAssignment().getCohort().getId().equals(cohort.getId()))
                    .filter(a -> a.getStatus() == AssignmentAttempt.Status.COMPLETED)
                    .map(a -> a.getAssignment().getId() + ":" + a.getStudent().getId())
                    .distinct()
                    .count();
            long cohortExpected = assigned * students;
            double cohortRate = cohortExpected == 0 ? 0.0
                    : Math.round(cohortCompleted * 10000.0 / cohortExpected) / 100.0;
            cohortRows.add(Map.of(
                    "cohortId", cohort.getId(),
                    "name", cohort.getName(),
                    "department", cohort.getDepartment() == null ? "" : cohort.getDepartment(),
                    "students", students,
                    "assignments", assigned,
                    "attempts", cohortAttempts,
                    "completed", cohortCompleted,
                    "completionRate", cohortRate));
        }

        Map<String, List<Integer>> byCriterion = new LinkedHashMap<>();
        CRITERIA.forEach(c -> byCriterion.put(c, new ArrayList<>()));
        List<Integer> overallScores = new ArrayList<>();
        int[] distribution = new int[4];
        Map<Long, List<Integer>> scoresByStudent = new LinkedHashMap<>();

        for (AssignmentAttempt attempt : attempts) {
            Interview interview = attempt.getInterview();
            if (interview == null) {
                continue;
            }
            if (interview.getReport() != null) {
                scoresByStudent.computeIfAbsent(attempt.getStudent().getId(), key -> new ArrayList<>())
                        .add(interview.getReport().getOverallScore());
            }
            for (InterviewSession session : interview.getSessions()) {
                Evaluation evaluation = session.getEvaluation();
                if (evaluation == null) {
                    continue;
                }
                if (evaluation.getScore() != null) {
                    overallScores.add(evaluation.getScore());
                    distribution[bucket(evaluation.getScore())]++;
                }
                readSkillScores(evaluation.getSkillScores(), byCriterion);
            }
        }

        Map<String, Object> averages = new LinkedHashMap<>();
        String weakest = null;
        double weakestValue = Double.MAX_VALUE;
        byCriterion.forEach((criterion, values) -> {
            Double average = values.isEmpty() ? null
                    : Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0) * 10.0) / 10.0;
            averages.put(criterion, average == null ? 0.0 : average);
        });
        for (Map.Entry<String, Object> entry : averages.entrySet()) {
            double value = ((Number) entry.getValue()).doubleValue();
            if (value > 0 && value < weakestValue) {
                weakestValue = value;
                weakest = entry.getKey();
            }
        }

        long students = cohorts.stream()
                .flatMap(c -> enrollmentRepository.findByCohortIdOrderByCreatedAtAsc(c.getId()).stream())
                .map(e -> e.getStudent().getId())
                .distinct()
                .count();
        double completionRate = expected == 0 ? 0.0
                : Math.round((completed * 10000.0 / expected)) / 100.0;
        double averageScore = overallScores.isEmpty() ? 0.0
                : Math.round(overallScores.stream().mapToInt(Integer::intValue).average().orElse(0) * 10.0) / 10.0;
        double attemptsPerStudent = students == 0 ? 0.0
                : Math.round(attempts.size() * 100.0 / students) / 100.0;

        double improvementTotal = 0;
        int improvedStudents = 0;
        for (List<Integer> scores : scoresByStudent.values()) {
            if (scores.size() >= 2) {
                improvementTotal += scores.get(scores.size() - 1) - scores.get(0);
                improvedStudents++;
            }
        }
        double scoreImprovement = improvedStudents == 0 ? 0.0
                : Math.round(improvementTotal * 100.0 / improvedStudents) / 100.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationId", organizationId);
        result.put("totalStudents", students);
        result.put("totalInterviews", interviewRepository.countByOrganizationId(organizationId));
        result.put("totalAssignments", assignments.size());
        result.put("totalAttempts", attempts.size());
        result.put("completedAttempts", completed);
        result.put("completionRate", completionRate);
        result.put("averageScore", averageScore);
        result.put("averageScoresByCriterion", averages);
        result.put("weakestCriterion", weakest);
        result.put("scoreDistribution", List.of(
                Map.of("band", "0-39", "count", distribution[0]),
                Map.of("band", "40-59", "count", distribution[1]),
                Map.of("band", "60-79", "count", distribution[2]),
                Map.of("band", "80-100", "count", distribution[3])));
        result.put("attemptsPerStudent", attemptsPerStudent);
        result.put("scoreImprovement", scoreImprovement);
        result.put("cohorts", cohortRows);
        return result;
    }

    /** Minimal CSV for a TPO spreadsheet; one row per assignment attempt. */
    @Transactional(readOnly = true)
    public String reportsCsv(Long organizationId) {
        tenantContext.requireMembership(organizationId);
        StringBuilder csv = new StringBuilder(
                "student,email,cohort,assignment,status,attempts,score,completedAt\n");
        for (AssignmentAttempt attempt : attemptRepository.findByAssignmentCohortOrganizationId(organizationId)) {
            Assignment assignment = attempt.getAssignment();
            User student = attempt.getStudent();
            Integer score = attempt.getInterview() != null && attempt.getInterview().getReport() != null
                    ? attempt.getInterview().getReport().getOverallScore() : null;
            csv.append(escape(student.getFullName())).append(',')
                    .append(escape(student.getEmail())).append(',')
                    .append(escape(assignment.getCohort().getName())).append(',')
                    .append(escape(assignment.getTitle())).append(',')
                    .append(attempt.getStatus().name()).append(',')
                    .append(attemptRepository.countByAssignmentIdAndStudentId(
                            assignment.getId(), student.getId())).append(',')
                    .append(score == null ? "" : score).append(',')
                    .append(attempt.getCompletedAt() == null ? "" : attempt.getCompletedAt())
                    .append('\n');
        }
        return csv.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace("\"", "\"\"");
        return clean.contains(",") || clean.contains("\"") || clean.contains("\n")
                ? "\"" + clean + "\"" : clean;
    }

    private int bucket(int score) {
        if (score < 40) return 0;
        if (score < 60) return 1;
        if (score < 80) return 2;
        return 3;
    }

    private void readSkillScores(String raw, Map<String, List<Integer>> byCriterion) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            node.fields().forEachRemaining(field -> {
                if (byCriterion.containsKey(field.getKey()) && field.getValue().isNumber()) {
                    byCriterion.get(field.getKey()).add(field.getValue().asInt());
                }
            });
        } catch (Exception ignored) {
            // A malformed legacy evaluation simply does not contribute.
        }
    }
}
