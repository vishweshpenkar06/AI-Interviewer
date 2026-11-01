package com.interviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.entity.Interview;
import com.interviewer.entity.JobDescription;
import com.interviewer.entity.Question;
import com.interviewer.entity.Resume;
import com.interviewer.service.AiServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PlacementOS Phase 1/2 acceptance: tenants, CSV import, assignments and authz. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(PlacementOsFlowTest.FakeAiConfig.class)
class PlacementOsFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class FakeAiConfig {
        @Bean
        @Primary
        AiServiceClient fakeAiServiceClient() {
            return new FakeAiServiceClient();
        }
    }

    static class FakeAiServiceClient extends AiServiceClient {
        FakeAiServiceClient() {
            super(2000);
        }

        @Override
        public List<Question> generateQuestions(JobDescription jd, Resume resume, int count) {
            return List.of();
        }

        @Override
        public String generateFollowUp(String question, String answer) {
            return "Can you go deeper?";
        }

        @Override
        public Map<String, Object> evaluateAnswer(String question, String answer, String expectedAnswer,
                                                  String questionType, String difficulty, String jobContext) {
            Map<String, Object> result = new HashMap<>();
            result.put("score", 80);
            result.put("grade", "A");
            result.put("feedback", "Clear and correct.");
            result.put("skill_scores", Map.of(
                    "relevance", 80, "technicalDepth", 70,
                    "correctness", 90, "clarity", 75, "evidence", 60));
            return result;
        }

        @Override
        public Map<String, Object> generateReport(Interview interview) {
            return Map.of();
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }

    @Test
    void companyTracksAreSeededWithValidWeights() throws Exception {
        String tpo = register("track-tpo");
        long orgId = createOrg(tpo, "Track College");

        MvcResult result = mvc.perform(get("/api/orgs/{id}/tracks", orgId).header("Authorization", bearer(tpo)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tracks = read(result);
        assertThat(tracks.size()).isEqualTo(5);
        Set<String> names = new HashSet<>();
        for (JsonNode track : tracks) {
            names.add(track.get("name").asText());
            double sum = 0;
            JsonNode weights = track.get("config").get("rubricWeights");
            for (Iterator<String> it = weights.fieldNames(); it.hasNext(); ) {
                sum += weights.get(it.next()).asDouble();
            }
            assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        }
        assertThat(names).contains("TCS NQT", "Infosys", "Wipro", "Product SDE", "Data Analyst");
    }

    @Test
    void tenantIsolationAndRoleChecks() throws Exception {
        String tpoA = register("tpo-a");
        String tpoB = register("tpo-b");
        long orgA = createOrg(tpoA, "Org A");
        long orgB = createOrg(tpoB, "Org B");
        long cohortA = createCohort(tpoA, orgA, "A 2027");
        createCohort(tpoB, orgB, "B 2027");

        // Cross-tenant reads are forbidden, not merely filtered.
        mvc.perform(get("/api/orgs/{id}", orgB).header("Authorization", bearer(tpoA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/cohorts", orgB).header("Authorization", bearer(tpoA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/analytics", orgB).header("Authorization", bearer(tpoA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/reports/export", orgB).header("Authorization", bearer(tpoB)))
                .andExpect(status().isOk());

        // A student cannot use TPO endpoints even in their own org.
        String student = register("student-authz");
        importCsv(tpoA, orgA, cohortA, csv("Student One", student));

        mvc.perform(post("/api/orgs/{id}/cohorts", orgA)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sneaky cohort"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/analytics", orgA).header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());

        // A student in org A cannot read or start anything in org B.
        long cohortB = read(mvc.perform(get("/api/orgs/{id}/cohorts", orgB)
                        .header("Authorization", bearer(tpoB)))
                .andExpect(status().isOk()).andReturn()).get(0).get("id").asLong();
        JsonNode orgBAssignment = read(mvc.perform(post("/api/orgs/{id}/assignments", orgB)
                        .header("Authorization", bearer(tpoB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cohortId", cohortB, "title", "Org B mock"))))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/orgs/{id}/cohorts", orgB).header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/assignments", orgB).header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orgs/{orgId}/assignments/{id}/start", orgB,
                        orgBAssignment.get("id").asLong())
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());

        // Analytics for org A never include org B's cohort.
        mvc.perform(get("/api/orgs/{id}/analytics", orgA).header("Authorization", bearer(tpoA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts[0].name").value("A 2027"))
                .andExpect(jsonPath("$.cohorts", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void csvImportValidatesDuplicatesAndErrors() throws Exception {
        String tpo = register("csv-tpo");
        long orgId = createOrg(tpo, "CSV College");
        long cohortId = createCohort(tpo, orgId, "CSV 2027");

        String existing = register("csv-existing");
        String csv = "fullName,email,phone,department,graduationYear\n"
                + "New One,new1-" + UUID.randomUUID() + "@test.local,,CSE,2027\n"
                + "Existing Student," + emailOf(existing) + ",,CSE,2027\n"
                + "Not An Email,broken-address,,CSE,2027\n";

        MvcResult result = importCsv(tpo, orgId, cohortId, csv);
        JsonNode body = read(result);
        assertThat(body.get("created").asInt()).isEqualTo(2);
        assertThat(body.get("errors")).hasSize(1);
        assertThat(body.get("errors").get(0).asText()).contains("invalid email");

        // A second import of the same existing student is a skip, not a duplicate.
        JsonNode again = read(importCsv(tpo, orgId, cohortId, csv));
        assertThat(again.get("skipped").asInt()).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/orgs/{id}/cohorts/{cohortId}/students", orgId, cohortId)
                        .header("Authorization", bearer(tpo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void assignmentLifecycleScoresWithTrackWeightsAndIsIsolated() throws Exception {
        String tpo = register("assign-tpo");
        String student = register("assign-student");
        long orgId = createOrg(tpo, "Placement College");
        long cohortId = createCohort(tpo, orgId, "CSE 2027");
        importCsv(tpo, orgId, cohortId, csv("Assigned Student", student));

        JsonNode tracks = read(mvc.perform(get("/api/orgs/{id}/tracks", orgId)
                        .header("Authorization", bearer(tpo)))
                .andExpect(status().isOk()).andReturn());
        long tcsTrack = 0;
        for (JsonNode track : tracks) {
            if ("TCS NQT".equals(track.get("name").asText())) {
                tcsTrack = track.get("id").asLong();
            }
        }
        assertThat(tcsTrack).isPositive();

        MvcResult assignmentResult = mvc.perform(post("/api/orgs/{id}/assignments", orgId)
                        .header("Authorization", bearer(tpo))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "cohortId", cohortId,
                                "trackId", tcsTrack,
                                "title", "TCS NQT mock",
                                "maxAttempts", 1))))
                .andExpect(status().isOk())
                .andReturn();
        long assignmentId = read(assignmentResult).get("id").asLong();

        // The student sees the assignment, the TPO cannot "take" it.
        mvc.perform(get("/api/me/assignments").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assignmentId").value(assignmentId))
                .andExpect(jsonPath("$[0].canStart").value(true));
        mvc.perform(post("/api/orgs/{orgId}/assignments/{id}/start", orgId, assignmentId)
                        .header("Authorization", bearer(tpo)))
                .andExpect(status().isForbidden());

        JsonNode started = read(mvc.perform(post("/api/orgs/{orgId}/assignments/{id}/start", orgId, assignmentId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andReturn());
        long interviewId = started.get("interviewId").asLong();
        assertThat(interviewId).isPositive();

        // Questions follow the track sections.
        JsonNode interview = read(mvc.perform(get("/api/interviews/{id}", interviewId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andReturn());
        Set<String> types = new HashSet<>();
        for (JsonNode session : interview.get("sessions")) {
            types.add(session.get("questionType").asText());
        }
        assertThat(types).contains("SITUATIONAL", "TECHNICAL", "BEHAVIORAL");

        mvc.perform(post("/api/interviews/{id}/begin", interviewId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        JsonNode question = read(mvc.perform(get("/api/interviews/{id}/next-question", interviewId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/interviews/{id}/answer", interviewId)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sessionId", question.get("id").asLong(),
                                "answer", "A complete answer with detail."))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/interviews/{id}/end", interviewId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());

        // Weighted score: .15*80 + .25*70 + .35*90 + .15*75 + .10*60 = 78.25 -> 78.
        mvc.perform(get("/api/reports/{id}", interviewId).header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(78));

        mvc.perform(get("/api/me/assignments").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].canStart").value(false));

        mvc.perform(post("/api/orgs/{orgId}/assignments/{id}/start", orgId, assignmentId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isConflict());

        // The TPO sees one completed attempt in analytics and export.
        mvc.perform(get("/api/orgs/{id}/analytics", orgId).header("Authorization", bearer(tpo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudents").value(1))
                .andExpect(jsonPath("$.completedAttempts").value(1))
                .andExpect(jsonPath("$.weakestCriterion").value("evidence"))
                .andExpect(jsonPath("$.averageScoresByCriterion.evidence").value(60.0));
        mvc.perform(get("/api/orgs/{id}/reports/export", orgId).header("Authorization", bearer(tpo)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("COMPLETED")));

        mvc.perform(get("/api/orgs/{id}/usage", orgId).header("Authorization", bearer(tpo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PILOT"))
                .andExpect(jsonPath("$.students").value(1))
                .andExpect(jsonPath("$.assignments").value(1))
                .andExpect(jsonPath("$.interviews").value(1))
                .andExpect(jsonPath("$.reports").value(1))
                .andExpect(jsonPath("$.storageBytes").isNumber());
    }

    @Test
    void privacyConsentExportAndDeletion() throws Exception {
        String tpo = register("privacy-tpo");
        String student = register("privacy-student");
        long orgId = createOrg(tpo, "Privacy College");
        long cohortId = createCohort(tpo, orgId, "Privacy 2027");
        importCsv(tpo, orgId, cohortId, csv("Privacy Student", student));

        // Voice is closed until recording consent is on.
        mvc.perform(multipart("/api/voice/transcribe")
                        .file(new MockMultipartFile("audio", "clip.webm", "audio/webm", "audio".getBytes()))
                        .header("Authorization", bearer(student)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/me/consent")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "consentRecording", true,
                                "consentProctoring", true,
                                "dataRetentionDays", 14))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentRecording").value(true))
                .andExpect(jsonPath("$.dataRetentionDays").value(14));

        mvc.perform(multipart("/api/voice/transcribe")
                        .file(new MockMultipartFile("audio", "clip.webm", "audio/webm", "audio".getBytes()))
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk());

        // Export includes the profile and memberships.
        mvc.perform(get("/api/me/data").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email").value(emailOf(student)))
                .andExpect(jsonPath("$.memberships", org.hamcrest.Matchers.hasSize(1)));

        // A completed interview is removed, and the profile is anonymised.
        JsonNode assignment = createGeneralAssignment(tpo, orgId, cohortId);
        long assignmentId = assignment.get("id").asLong();
        long interviewId = read(mvc.perform(post("/api/orgs/{orgId}/assignments/{id}/start", orgId, assignmentId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andReturn()).get("interviewId").asLong();
        mvc.perform(post("/api/interviews/{id}/begin", interviewId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/interviews/{id}/end", interviewId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/me/data").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedInterviews").value(1));
        mvc.perform(get("/api/interviews").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
        mvc.perform(get("/api/users/profile").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Deleted User"))
                .andExpect(jsonPath("$.phone").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.consentRecording").value(false));
    }

    private JsonNode createGeneralAssignment(String tpo, long orgId, long cohortId) throws Exception {
        return read(mvc.perform(post("/api/orgs/{id}/assignments", orgId)
                        .header("Authorization", bearer(tpo))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "cohortId", cohortId,
                                "title", "General mock",
                                "maxAttempts", 2))))
                .andExpect(status().isOk()).andReturn());
    }

    private String register(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@test.local";
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "fullName", "Test " + prefix))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("token").asText();
    }

    private String emailOf(String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/users/profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("email").asText();
    }

    private long createOrg(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/orgs")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("id").asLong();
    }

    private long createCohort(String token, long orgId, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/orgs/{id}/cohorts", orgId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", name,
                                "department", "CSE",
                                "graduationYear", 2027))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("id").asLong();
    }

    private MvcResult importCsv(String token, long orgId, long cohortId, String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(multipart("/api/orgs/{id}/cohorts/{cohortId}/students/import", orgId, cohortId)
                        .file(file)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String csv(String fullName, String token) throws Exception {
        return "fullName,email,phone,department,graduationYear\n"
                + fullName + "," + emailOf(token) + ",,CSE,2027\n";
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
