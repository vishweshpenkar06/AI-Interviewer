package com.interviewer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.dto.*;
import com.interviewer.entity.*;
import com.interviewer.exception.ConflictException;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRepository interviewRepository;
    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final EvaluationRepository evaluationRepository;
    private final ReportRepository reportRepository;
    private final AssignmentAttemptRepository assignmentAttemptRepository;
    private final TenantContext tenantContext;
    private final AiServiceClient aiServiceClient;
    private final QuestionBankService questionBankService;
    private final ObjectMapper objectMapper;

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Loads an interview only when it belongs to the caller; otherwise behaves as if it does not exist. */
    private Interview requireOwnedInterview(Long interviewId, Long userId) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found"));
        if (userId == null || interview.getCandidate() == null
                || !interview.getCandidate().getId().equals(userId)) {
            throw new ResourceNotFoundException("Interview not found");
        }
        return interview;
    }

    @Transactional
    public InterviewResponse startInterview(Long userId, InterviewStartRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        JobDescription jd = jobDescriptionRepository.findById(request.getJobDescriptionId())
                .orElseThrow(() -> new ResourceNotFoundException("Job description not found"));
        if (jd.getCreatedBy() == null || !jd.getCreatedBy().getId().equals(userId)) {
            throw new ResourceNotFoundException("Job description not found");
        }

        Resume resume = null;
        if (request.getResumeId() != null) {
            resume = resumeRepository.findById(request.getResumeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));
            if (!resume.getUser().getId().equals(userId)) {
                throw new ResourceNotFoundException("Resume not found");
            }
        }

        Interview interview = Interview.builder()
                .candidate(user)
                .organizationId(tenantContext.currentOrganizationId())
                .jobDescription(jd)
                .resume(resume)
                .title(request.getTitle())
                .mode(Interview.InterviewMode.valueOf(request.getMode()))
                .totalQuestions(request.getTotalQuestions())
                .durationMinutes(request.getDurationMinutes())
                .notes(request.getNotes())
                .status(Interview.Status.SCHEDULED)
                .build();

        interview = interviewRepository.save(interview);

        List<Question> questions = generateQuestions(jd, resume, request.getTotalQuestions());

        List<InterviewSession> sessions = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            InterviewSession session = InterviewSession.builder()
                    .interview(interview)
                    .question(questions.get(i))
                    .questionOrder(i + 1)
                    .status(InterviewSession.Status.PENDING)
                    .build();
            sessions.add(session);
        }

        sessionRepository.saveAll(sessions);
        interview.setSessions(sessions);
        // Sessions are the source of truth; keep the advertised count in sync with what exists.
        interview.setTotalQuestions(sessions.size());
        interviewRepository.save(interview);

        return mapToInterviewResponse(interview);
    }

    private List<Question> generateQuestions(JobDescription jd, Resume resume, int count) {
        // Try question bank first (fast, no API calls)
        String roleTitle = jd.getTitle();
        List<Map<String, String>> bankQuestions = questionBankService.getQuestionsForRole(roleTitle, count);

        if (!bankQuestions.isEmpty() && bankQuestions.size() >= count) {
            List<Question> questions = new ArrayList<>();
            for (Map<String, String> bq : bankQuestions) {
                questions.add(questionFromBank(bq, null));
            }
            return questionRepository.saveAll(questions);
        }

        // Fallback to AI generation
        try {
            List<Question> aiQuestions = aiServiceClient.generateQuestions(jd, resume, count);
            if (aiQuestions != null && !aiQuestions.isEmpty()) {
                for (Question q : aiQuestions) {
                    if (q.getActive() == null) {
                        q.setActive(true);
                    }
                }
                return questionRepository.saveAll(aiQuestions);
            }
        } catch (Exception e) {
            // Fallback to database questions
        }

        List<Question> questions = questionRepository.findByActiveTrue();
        return questions.stream().limit(count).collect(Collectors.toList());
    }

    @Transactional
    public InterviewResponse startInterviewSession(Long interviewId, Long userId) {
        Interview interview = requireOwnedInterview(interviewId, userId);

        if (interview.getStatus() == Interview.Status.IN_PROGRESS) {
            return mapToInterviewResponse(interview);
        }
        if (interview.getStatus() != Interview.Status.SCHEDULED) {
            throw new ConflictException("Interview cannot be started in status " + interview.getStatus());
        }

        interview.setStatus(Interview.Status.IN_PROGRESS);
        interview.setStartedAt(LocalDateTime.now());
        interview = interviewRepository.save(interview);

        return mapToInterviewResponse(interview);
    }

    @Transactional(readOnly = true)
    public SessionResponse getNextQuestion(Long interviewId, Long userId) {
        Interview interview = requireOwnedInterview(interviewId, userId);

        if (interview.getStatus() != Interview.Status.IN_PROGRESS) {
            throw new ConflictException("Interview is not in progress");
        }

        int nextOrder = interview.getCurrentQuestionIndex() + 1;
        if (nextOrder > interview.getTotalQuestions()) {
            throw new ConflictException("No more questions");
        }

        InterviewSession session = sessionRepository
                .findByInterviewIdAndQuestionOrder(interviewId, nextOrder)
                .orElseThrow(() -> new ConflictException("No more questions"));

        return mapToSessionResponse(session, false);
    }

    @Transactional
    public SessionResponse submitAnswer(Long interviewId, Long userId, AnswerSubmitRequest request) {
        Interview interview = requireOwnedInterview(interviewId, userId);

        if (interview.getStatus() != Interview.Status.IN_PROGRESS) {
            throw new ConflictException("Interview is not in progress");
        }

        InterviewSession session = sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("Question not found"));

        if (!session.getInterview().getId().equals(interviewId)) {
            throw new ResourceNotFoundException("Question not found");
        }

        if (session.getStatus() == InterviewSession.Status.ANSWERED
                || session.getStatus() == InterviewSession.Status.EVALUATED) {
            // Idempotent retry: never evaluate twice, never advance the index twice.
            if (session.getEvaluation() == null) {
                evaluateSession(session, interview);
                sessionRepository.save(session);
            }
            return mapToSessionResponse(session, false);
        }

        if (!session.getQuestionOrder().equals(interview.getCurrentQuestionIndex() + 1)) {
            throw new ConflictException("Answers must be submitted in order");
        }

        session.setCandidateAnswer(request.getAnswer());
        session.setStatus(InterviewSession.Status.ANSWERED);
        session.setAnsweredAt(LocalDateTime.now());
        session = sessionRepository.save(session);

        evaluateSession(session, interview);

        if (session.getEvaluation() != null) {
            adaptNextQuestion(interview, session);
        }

        interview.setCurrentQuestionIndex(session.getQuestionOrder());
        interviewRepository.save(interview);

        return mapToSessionResponse(session, false);
    }

    /**
     * Keeps the pre-planned next session but swaps its question when the last answer
     * was clearly strong or weak, so the rest of the interview follows the candidate.
     * A missing bank entry simply leaves the original question in place.
     */
    private void adaptNextQuestion(Interview interview, InterviewSession answered) {
        int nextOrder = answered.getQuestionOrder() + 1;
        if (nextOrder > interview.getTotalQuestions()) {
            return;
        }
        InterviewSession next = sessionRepository
                .findByInterviewIdAndQuestionOrder(interview.getId(), nextOrder)
                .orElse(null);
        if (next == null) {
            return;
        }

        int score = answered.getEvaluation().getScore();
        String target = score >= 80 ? "HARD" : score <= 50 ? "EASY" : null;
        if (target == null || next.getQuestion().getDifficulty().name().equals(target)) {
            return;
        }

        String roleTitle = interview.getJobDescription() != null
                ? interview.getJobDescription().getTitle() : null;
        if (roleTitle == null || roleTitle.isBlank()) {
            return;
        }

        Set<String> usedTexts = interview.getSessions().stream()
                .map(s -> s.getQuestion().getText())
                .collect(Collectors.toSet());

        questionBankService.pickQuestion(roleTitle, target, usedTexts).ifPresent(bank -> {
            Question replacement = questionRepository.save(questionFromBank(bank, target));
            next.setQuestion(replacement);
            sessionRepository.save(next);
        });
    }

    /** Tolerates bad bank data (unknown type/difficulty) instead of failing the whole interview. */
    private Question questionFromBank(Map<String, String> bank, String forcedDifficulty) {
        String difficulty = forcedDifficulty != null ? forcedDifficulty : bank.get("difficulty");
        return Question.builder()
                .text(bank.get("text"))
                .type(parseEnum(Question.Type.class, bank.get("type"), Question.Type.TECHNICAL))
                .difficulty(parseEnum(Question.Difficulty.class, difficulty, Question.Difficulty.MEDIUM))
                .expectedAnswer(bank.get("expected_answer"))
                .active(true)
                .build();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Best-effort evaluation. Failure leaves the answer saved (status ANSWERED) so a retry can evaluate it. */
    private void evaluateSession(InterviewSession session, Interview interview) {
        String questionText = session.getQuestion().getText();
        String candidateAnswer = session.getCandidateAnswer();
        String expectedAnswer = session.getQuestion().getExpectedAnswer();
        String jobContext = interview.getJobDescription() != null
                ? interview.getJobDescription().getTitle() : "";

        // The follow-up and the rubric score are independent model calls. Running
        // them together halves the time the candidate waits after submitting.
        CompletableFuture<String> followUpFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return aiServiceClient.generateFollowUp(questionText, candidateAnswer);
            } catch (Exception e) {
                return null;
            }
        });
        CompletableFuture<Map<String, Object>> evalFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return aiServiceClient.evaluateAnswer(
                        questionText,
                        candidateAnswer,
                        expectedAnswer,
                        session.getQuestion().getType().name(),
                        session.getQuestion().getDifficulty().name(),
                        jobContext
                );
            } catch (Exception e) {
                return null;
            }
        });

        String followUp = followUpFuture.join();
        if (followUp != null) {
            session.setAiFollowUp(followUp);
        }

        Map<String, Object> result = evalFuture.join();
        int score = applyTrackWeights(result, weightsFor(interview));
        applyEvaluation(session, result, score);
    }

    /**
     * The track may weight criteria differently from the default rubric. The
     * per-criterion scores (already evidence-capped by the rubric) are weighted
     * here so a TCS NQT attempt and a Product SDE attempt score honestly.
     */
    private int applyTrackWeights(Map<String, Object> evalResult, Map<String, Double> weights) {
        if (evalResult == null || evalResult.get("skill_scores") == null || weights == null) {
            return evalResult != null && evalResult.get("score") != null
                    ? ((Number) evalResult.get("score")).intValue() : 0;
        }
        Object raw = evalResult.get("skill_scores");
        if (!(raw instanceof Map<?, ?> skills)) {
            return ((Number) evalResult.getOrDefault("score", 0)).intValue();
        }
        double total = 0;
        double weightSum = 0;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            String key = "technical_depth".equals(entry.getKey()) ? "technicalDepth" : entry.getKey();
            Object value = skills.get(key);
            if (value instanceof Number number) {
                total += entry.getValue() * number.doubleValue();
                weightSum += entry.getValue();
            }
        }
        if (weightSum <= 0) {
            return ((Number) evalResult.getOrDefault("score", 0)).intValue();
        }
        int score = (int) Math.round(total / weightSum);
        evalResult.put("score", score);
        evalResult.put("grade", gradeFor(score));
        return score;
    }

    private Map<String, Double> weightsFor(Interview interview) {
        if (interview.getInterviewConfig() == null || interview.getInterviewConfig().isBlank()) {
            return null;
        }
        try {
            JsonNode config = objectMapper.readTree(interview.getInterviewConfig());
            JsonNode weights = config.get("rubricWeights");
            if (weights == null || !weights.isObject()) {
                return null;
            }
            Map<String, Double> parsed = new LinkedHashMap<>();
            weights.fields().forEachRemaining(field -> {
                if (field.getValue().isNumber()) {
                    parsed.put(field.getKey(), field.getValue().asDouble());
                }
            });
            double sum = parsed.values().stream().mapToDouble(Double::doubleValue).sum();
            return Math.abs(sum - 1.0) <= 0.01 && parsed.size() == 5 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyEvaluation(InterviewSession session, Map<String, Object> evalResult, int computedScore) {
        if (evalResult == null || evalResult.isEmpty() || evalResult.get("score") == null) {
            return;
        }
        Evaluation evaluation = Evaluation.builder()
                .session(session)
                .score(computedScore)
                .grade((String) evalResult.getOrDefault("grade", "F"))
                .feedback((String) evalResult.getOrDefault("feedback", ""))
                .skillScores(evalResult.get("skill_scores") != null ? toJson(evalResult.get("skill_scores")) : null)
                .strengths(evalResult.get("strengths") != null ? toJson(evalResult.get("strengths")) : null)
                .weaknesses(evalResult.get("weaknesses") != null ? toJson(evalResult.get("weaknesses")) : null)
                .improvementSuggestions((String) evalResult.get("improvement_suggestions"))
                .sampleResponse((String) evalResult.getOrDefault("sample_response", ""))
                .confidenceLevel((String) evalResult.get("confidence_level"))
                .communicationScore((String) evalResult.get("communication_score"))
                .technicalDepth((String) evalResult.get("technical_depth"))
                .build();
        evaluation = evaluationRepository.save(evaluation);
        session.setEvaluation(evaluation);
        session.setStatus(InterviewSession.Status.EVALUATED);
        sessionRepository.save(session);
    }

    @Transactional
    public Report generateReportForInterview(Interview interview) {
        Report existing = reportRepository.findByInterviewId(interview.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }

        List<InterviewSession> evaluated = interview.getSessions().stream()
                .filter(s -> s.getEvaluation() != null && s.getEvaluation().getScore() != null)
                .toList();

        int overallScore = 0;
        String overallGrade = "N/A";
        String recommendationLevel = "INSUFFICIENT_DATA";
        String summary;
        if (evaluated.isEmpty()) {
            summary = "No answers were evaluated for this interview, so no score can be calculated. "
                    + "This is not a judgement of the candidate.";
        } else {
            overallScore = (int) Math.round(evaluated.stream()
                    .mapToInt(s -> s.getEvaluation().getScore()).average().orElse(0));
            overallGrade = gradeFor(overallScore);
            recommendationLevel = overallScore >= 70 ? "RECOMMENDED"
                    : overallScore >= 50 ? "BORDERLINE" : "NOT_RECOMMENDED";
            summary = "Score computed from " + evaluated.size() + " evaluated answer(s) out of "
                    + interview.getSessions().size() + " question(s).";
        }

        String recommendations = "Review the per-question feedback for concrete next steps.";
        // Derived from real per-answer criterion scores, never from the model's prose.
        String skillRadar = toJson(buildSkillRadar(evaluated));
        String categoryScores = toJson(buildCategoryScores(evaluated));
        String strengths = "[]";
        String weaknesses = "[]";
        String breakdown = toJson(buildQuestionBreakdown(interview));

        Map<String, Object> reportData;
        try {
            reportData = aiServiceClient.generateReport(interview);
        } catch (Exception e) {
            reportData = null;
        }

        if (reportData != null && !reportData.isEmpty()) {
            // Only prose may come from the model; every number is computed from real evaluations.
            if (reportData.get("summary") != null) {
                summary = (String) reportData.get("summary");
            }
            if (reportData.get("strengths") != null) {
                strengths = toJson(reportData.get("strengths"));
            }
            if (reportData.get("weaknesses") != null) {
                weaknesses = toJson(reportData.get("weaknesses"));
            }
            if (reportData.get("recommendations") != null) {
                recommendations = (String) reportData.get("recommendations");
            }
        }

        Report report = Report.builder()
                .interview(interview)
                .organizationId(interview.getOrganizationId())
                .overallScore(overallScore)
                .overallGrade(overallGrade)
                .summary(summary)
                .skillRadarData(skillRadar)
                .categoryScores(categoryScores)
                .strengths(strengths)
                .weaknesses(weaknesses)
                .recommendations(recommendations)
                .recommendationLevel(recommendationLevel)
                .questionBreakdown(breakdown)
                .build();

        return reportRepository.save(report);
    }

    private static final Map<String, String> SKILL_LABELS = Map.of(
            "relevance", "Relevance",
            "technicalDepth", "Technical Depth",
            "correctness", "Correctness",
            "clarity", "Clarity",
            "evidence", "Evidence");

    /** Averages each rubric criterion across evaluated answers. Empty when no criterion scores exist. */
    private List<Map<String, Object>> buildSkillRadar(List<InterviewSession> evaluated) {
        Map<String, List<Integer>> bySkill = new LinkedHashMap<>();
        for (InterviewSession session : evaluated) {
            String raw = session.getEvaluation().getSkillScores();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(raw);
                node.fields().forEachRemaining(field -> {
                    if (field.getValue().isNumber()) {
                        bySkill.computeIfAbsent(field.getKey(), key -> new ArrayList<>())
                                .add(field.getValue().asInt());
                    }
                });
            } catch (Exception ignored) {
                // A legacy or malformed evaluation simply does not contribute to the radar.
            }
        }

        List<Map<String, Object>> radar = new ArrayList<>();
        bySkill.forEach((skill, scores) -> radar.add(Map.of(
                "skill", SKILL_LABELS.getOrDefault(skill, skill),
                "score", (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0)))));
        return radar;
    }

    private List<Map<String, Object>> buildCategoryScores(List<InterviewSession> evaluated) {
        Map<String, List<Integer>> byType = new LinkedHashMap<>();
        for (InterviewSession session : evaluated) {
            byType.computeIfAbsent(prettyType(session.getQuestion().getType().name()), key -> new ArrayList<>())
                    .add(session.getEvaluation().getScore());
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        byType.forEach((category, scores) -> categories.add(Map.of(
                "category", category,
                "score", (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0)))));
        return categories;
    }

    private String prettyType(String name) {
        String lower = name.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private List<Map<String, Object>> buildQuestionBreakdown(Interview interview) {
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (InterviewSession session : interview.getSessions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", session.getQuestion().getText());
            item.put("questionOrder", session.getQuestionOrder());
            item.put("questionType", session.getQuestion().getType().name());
            item.put("status", session.getStatus().name());
            item.put("answer", session.getCandidateAnswer());
            item.put("expectedAnswer", session.getQuestion().getExpectedAnswer());
            if (session.getEvaluation() != null) {
                item.put("score", session.getEvaluation().getScore());
                item.put("grade", session.getEvaluation().getGrade());
                item.put("feedback", session.getEvaluation().getFeedback());
                item.put("improvementSuggestions", session.getEvaluation().getImprovementSuggestions());
                item.put("sampleResponse", session.getEvaluation().getSampleResponse());
            }
            breakdown.add(item);
        }
        return breakdown;
    }

    private String gradeFor(int score) {
        if (score >= 90) return "A+";
        if (score >= 80) return "A";
        if (score >= 70) return "B+";
        if (score >= 60) return "B";
        if (score >= 50) return "C+";
        if (score >= 40) return "C";
        if (score >= 30) return "D";
        return "F";
    }

    @Transactional
    public InterviewResponse endInterview(Long interviewId, Long userId) {
        Interview interview = requireOwnedInterview(interviewId, userId);

        if (interview.getStatus() == Interview.Status.COMPLETED) {
            return mapToInterviewResponse(interview);
        }
        if (interview.getStatus() != Interview.Status.IN_PROGRESS) {
            throw new ConflictException("Interview cannot be completed in status " + interview.getStatus());
        }

        interview.setStatus(Interview.Status.COMPLETED);
        interview.setCompletedAt(LocalDateTime.now());
        interview = interviewRepository.save(interview);

        generateReportForInterview(interview);

        assignmentAttemptRepository.findByInterviewId(interview.getId()).ifPresent(attempt -> {
            attempt.setStatus(AssignmentAttempt.Status.COMPLETED);
            attempt.setCompletedAt(LocalDateTime.now());
            assignmentAttemptRepository.save(attempt);
        });

        return mapToInterviewResponse(interview);
    }

    @Transactional(readOnly = true)
    public List<InterviewResponse> getUserInterviews(Long userId) {
        return interviewRepository.findByCandidateIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToInterviewResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InterviewResponse getInterview(Long interviewId, Long userId) {
        return mapToInterviewResponse(requireOwnedInterview(interviewId, userId));
    }

    private InterviewResponse mapToInterviewResponse(Interview interview) {
        boolean completed = interview.getStatus() == Interview.Status.COMPLETED;
        Report report = interview.getReport();

        return InterviewResponse.builder()
                .id(interview.getId())
                .title(interview.getTitle())
                .status(interview.getStatus().name())
                .mode(interview.getMode().name())
                .totalQuestions(interview.getTotalQuestions())
                .currentQuestionIndex(interview.getCurrentQuestionIndex())
                .durationMinutes(interview.getDurationMinutes())
                .createdAt(interview.getCreatedAt())
                .startedAt(interview.getStartedAt())
                .completedAt(interview.getCompletedAt())
                .overallScore(report != null ? report.getOverallScore() : null)
                .overallGrade(report != null ? report.getOverallGrade() : null)
                .jobDescriptionTitle(interview.getJobDescription() != null ? interview.getJobDescription().getTitle() : null)
                .companyName(interview.getJobDescription() != null ? interview.getJobDescription().getCompany() : null)
                .sessions(interview.getSessions().stream()
                        .map(session -> mapToSessionResponse(session, completed))
                        .collect(Collectors.toList()))
                .build();
    }

    private SessionResponse mapToSessionResponse(InterviewSession session, boolean includeExpectedAnswer) {
        SessionResponse.SessionResponseBuilder builder = SessionResponse.builder()
                .id(session.getId())
                .questionOrder(session.getQuestionOrder())
                .questionText(session.getQuestion().getText())
                .questionType(session.getQuestion().getType().name())
                .difficulty(session.getQuestion().getDifficulty().name())
                .expectedAnswer(includeExpectedAnswer ? session.getQuestion().getExpectedAnswer() : null)
                .candidateAnswer(session.getCandidateAnswer())
                .aiFollowUp(session.getAiFollowUp())
                .followUpAnswer(session.getFollowUpAnswer())
                .status(session.getStatus().name());

        if (session.getEvaluation() != null) {
            builder.evaluation(EvaluationResponse.builder()
                    .id(session.getEvaluation().getId())
                    .score(session.getEvaluation().getScore())
                    .grade(session.getEvaluation().getGrade())
                    .feedback(session.getEvaluation().getFeedback())
                    .strengths(session.getEvaluation().getStrengths())
                    .weaknesses(session.getEvaluation().getWeaknesses())
                    .improvementSuggestions(session.getEvaluation().getImprovementSuggestions())
                    .sampleResponse(session.getEvaluation().getSampleResponse())
                    .confidenceLevel(session.getEvaluation().getConfidenceLevel())
                    .communicationScore(session.getEvaluation().getCommunicationScore())
                    .technicalDepth(session.getEvaluation().getTechnicalDepth())
                    .build());
        }

        return builder.build();
    }
}
