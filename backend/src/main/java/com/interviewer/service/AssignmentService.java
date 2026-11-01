package com.interviewer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.dto.AssignmentResponse;
import com.interviewer.dto.CreateAssignmentRequest;
import com.interviewer.dto.StudentAssignmentResponse;
import com.interviewer.entity.*;
import com.interviewer.exception.ConflictException;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssignmentService {

    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "relevance", 0.15, "technical_depth", 0.30, "correctness", 0.30,
            "clarity", 0.15, "evidence", 0.10);

    private final AssignmentRepository assignmentRepository;
    private final AssignmentAttemptRepository attemptRepository;
    private final CohortRepository cohortRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final MembershipRepository membershipRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final CompanyTrackService companyTrackService;
    private final QuestionBankService questionBankService;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    @Transactional
    public AssignmentResponse create(Long organizationId, CreateAssignmentRequest request) {
        tenantContext.requireMembership(organizationId);
        Cohort cohort = cohortRepository.findByIdAndOrganizationId(request.getCohortId(), organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort not found"));

        CompanyTrack track = null;
        if (request.getTrackId() != null) {
            track = companyTrackService.findAvailable(request.getTrackId(), organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Company track not found"));
        }

        Assignment assignment = assignmentRepository.save(Assignment.builder()
                .cohort(cohort)
                .track(track)
                .title(request.getTitle().trim())
                .dueAt(request.getDueAt())
                .maxAttempts(request.getMaxAttempts() == null ? 1 : request.getMaxAttempts())
                .build());

        return toResponse(assignment, 0, 0);
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> list(Long organizationId) {
        tenantContext.requireMembership(organizationId);
        return assignmentRepository.findByCohortOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(a -> {
                    List<AssignmentAttempt> attempts = attemptRepository.findByAssignmentIdOrderByIdAsc(a.getId());
                    long completed = attempts.stream()
                            .filter(attempt -> attempt.getStatus() == AssignmentAttempt.Status.COMPLETED)
                            .count();
                    return toResponse(a, attempts.size(), completed);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StudentAssignmentResponse> myAssignments(Long userId) {
        List<Enrollment> enrollments = enrollmentRepository.findByStudentId(userId);
        List<StudentAssignmentResponse> result = new ArrayList<>();
        for (Enrollment enrollment : enrollments) {
            Cohort cohort = enrollment.getCohort();
            for (Assignment assignment : assignmentRepository.findByCohortIdOrderByCreatedAtDesc(cohort.getId())) {
                result.add(toStudentResponse(assignment, userId));
            }
        }
        result.sort(Comparator.comparing(StudentAssignmentResponse::getDueAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    /**
     * Starts (or resumes) an attempt. A scheduled/in-progress interview is
     * returned instead of burning another attempt.
     */
    @Transactional
    public StudentAssignmentResponse start(Long organizationId, Long assignmentId, Long userId) {
        Membership membership = tenantContext.requireMembership(organizationId);
        if (membership.getRole() != Membership.Role.STUDENT) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only students start assigned interviews");
        }
        Assignment assignment = assignmentRepository.findByIdAndCohortOrganizationId(assignmentId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        if (!enrollmentRepository.existsByCohortIdAndStudentId(assignment.getCohort().getId(), userId)) {
            throw new ResourceNotFoundException("Assignment not found");
        }
        if (assignment.getDueAt() != null && assignment.getDueAt().isBefore(LocalDateTime.now())) {
            throw new ConflictException("This assignment is past its due date");
        }

        List<AssignmentAttempt> attempts =
                attemptRepository.findByAssignmentIdAndStudentIdOrderByCreatedAtDesc(assignmentId, userId);
        for (AssignmentAttempt attempt : attempts) {
            if (attempt.getStatus() == AssignmentAttempt.Status.IN_PROGRESS
                    && attempt.getInterview() != null
                    && attempt.getInterview().getStatus() != Interview.Status.COMPLETED) {
                return toStudentResponse(assignment, userId);
            }
        }
        if (attempts.size() >= assignment.getMaxAttempts()) {
            throw new ConflictException("No attempts left for this assignment");
        }

        User student = tenantContext.requireUser(userId);
        CompanyTrackService.TrackConfig config = configFor(assignment);
        Interview interview = createInterview(assignment, student, config);

        attemptRepository.save(AssignmentAttempt.builder()
                .assignment(assignment)
                .student(student)
                .interview(interview)
                .status(AssignmentAttempt.Status.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .build());

        return toStudentResponse(assignment, userId);
    }

    /** Called by InterviewService when the interview completes. */
    @Transactional
    public void markCompletedForInterview(Long interviewId) {
        attemptRepository.findByInterviewId(interviewId).ifPresent(attempt -> {
            if (attempt.getStatus() != AssignmentAttempt.Status.COMPLETED) {
                attempt.setStatus(AssignmentAttempt.Status.COMPLETED);
                attempt.setCompletedAt(LocalDateTime.now());
                attemptRepository.save(attempt);
            }
        });
    }

    private Interview createInterview(Assignment assignment, User student,
                                      CompanyTrackService.TrackConfig config) {
        Interview interview = interviewRepository.save(Interview.builder()
                .candidate(student)
                .organizationId(assignment.getCohort().getOrganization().getId())
                .title(assignment.getTitle())
                .mode(Interview.InterviewMode.TEXT)
                .status(Interview.Status.SCHEDULED)
                .totalQuestions(config.questionCount())
                .durationMinutes(config.durationMinutes())
                .interviewConfig(toJson(config))
                .notes("Assigned via " + assignment.getCohort().getName())
                .build());

        List<Question> questions = buildQuestions(config);
        List<InterviewSession> sessions = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            sessions.add(InterviewSession.builder()
                    .interview(interview)
                    .question(questions.get(i))
                    .questionOrder(i + 1)
                    .status(InterviewSession.Status.PENDING)
                    .build());
        }
        sessionRepository.saveAll(sessions);
        interview.setSessions(sessions);
        interview.setTotalQuestions(sessions.size());
        return interviewRepository.save(interview);
    }

    /**
     * Questions follow the track's section order, so a TCS NQT assignment really
     * contains aptitude, technical and HR questions in proportion.
     */
    private List<Question> buildQuestions(CompanyTrackService.TrackConfig config) {
        String role = config.questionBankRole() == null || config.questionBankRole().isBlank()
                ? "backend-developer" : config.questionBankRole();
        List<Map<String, String>> bank = questionBankService.getQuestionsForRole(role, config.questionCount());
        if (bank.isEmpty()) {
            bank = questionBankService.getQuestionsForRole("backend-developer", config.questionCount());
        }
        Question.Difficulty difficulty;
        try {
            difficulty = Question.Difficulty.valueOf(config.difficulty().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            difficulty = Question.Difficulty.MEDIUM;
        }

        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < config.questionCount(); i++) {
            String section = config.sections().get(i % config.sections().size());
            Map<String, String> source = bank.isEmpty() ? Map.of() : bank.get(i % bank.size());
            String text = source.getOrDefault("text",
                    "Walk me through a project where you used " + section + " skills.");
            Question.Type type;
            try {
                type = Question.Type.valueOf(CompanyTrackService.questionTypeForSection(section));
            } catch (IllegalArgumentException e) {
                type = Question.Type.TECHNICAL;
            }
            questions.add(questionRepository.save(Question.builder()
                    .text(text)
                    .type(type)
                    .difficulty(difficulty)
                    .expectedAnswer(source.get("expected_answer"))
                    .active(true)
                    .build()));
        }
        return questions;
    }

    private CompanyTrackService.TrackConfig configFor(Assignment assignment) {
        if (assignment.getTrack() != null) {
            return companyTrackService.configOf(assignment.getTrack());
        }
        return new CompanyTrackService.TrackConfig(
                "General interview", "general", 60, 10, "medium",
                DEFAULT_WEIGHTS, List.of("technical", "hr"), "backend-developer");
    }

    private StudentAssignmentResponse toStudentResponse(Assignment assignment, Long userId) {
        List<AssignmentAttempt> attempts =
                attemptRepository.findByAssignmentIdAndStudentIdOrderByCreatedAtDesc(assignment.getId(), userId);
        long used = attempts.size();
        AssignmentAttempt latest = attempts.isEmpty() ? null : attempts.get(0);
        boolean duePassed = assignment.getDueAt() != null && assignment.getDueAt().isBefore(LocalDateTime.now());
        boolean canStart = used < assignment.getMaxAttempts() && !duePassed;
        Integer score = latest != null && latest.getInterview() != null
                && latest.getInterview().getReport() != null
                ? latest.getInterview().getReport().getOverallScore() : null;

        return StudentAssignmentResponse.builder()
                .assignmentId(assignment.getId())
                .organizationId(assignment.getCohort().getOrganization().getId())
                .organizationName(assignment.getCohort().getOrganization().getName())
                .cohortId(assignment.getCohort().getId())
                .cohortName(assignment.getCohort().getName())
                .trackId(assignment.getTrack() != null ? assignment.getTrack().getId() : null)
                .trackName(assignment.getTrack() != null ? assignment.getTrack().getName() : "General")
                .title(assignment.getTitle())
                .dueAt(assignment.getDueAt())
                .maxAttempts(assignment.getMaxAttempts())
                .attemptsUsed(used)
                .status(latest != null ? latest.getStatus().name() : "ASSIGNED")
                .interviewId(latest != null && latest.getInterview() != null
                        ? latest.getInterview().getId() : null)
                .score(score)
                .canStart(canStart)
                .build();
    }

    private AssignmentResponse toResponse(Assignment assignment, long attempts, long completed) {
        return AssignmentResponse.builder()
                .id(assignment.getId())
                .cohortId(assignment.getCohort().getId())
                .cohortName(assignment.getCohort().getName())
                .trackId(assignment.getTrack() != null ? assignment.getTrack().getId() : null)
                .trackName(assignment.getTrack() != null ? assignment.getTrack().getName() : "General")
                .title(assignment.getTitle())
                .dueAt(assignment.getDueAt())
                .maxAttempts(assignment.getMaxAttempts())
                .createdAt(assignment.getCreatedAt())
                .attemptCount(attempts)
                .completedCount(completed)
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
