package com.interviewer.service;

import com.interviewer.dto.*;
import com.interviewer.entity.*;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class CohortService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 5000;

    private final CohortRepository cohortRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final AssignmentRepository assignmentRepository;
    private final TenantContext tenantContext;
    private final PasswordEncoder passwordEncoder;
    private final InviteMailer inviteMailer;

    @Transactional
    public CohortResponse create(Long organizationId, CreateCohortRequest request) {
        Organization organization = tenantContext.requireMembership(organizationId).getOrganization();
        Cohort cohort = cohortRepository.save(Cohort.builder()
                .organization(organization)
                .name(request.getName().trim())
                .department(request.getDepartment())
                .graduationYear(request.getGraduationYear())
                .build());
        return toResponse(cohort, 0, 0);
    }

    @Transactional(readOnly = true)
    public List<CohortResponse> list(Long organizationId) {
        tenantContext.requireMembership(organizationId);
        return cohortRepository.findByOrganizationIdOrderByIdDesc(organizationId).stream()
                .map(c -> toResponse(c,
                        enrollmentRepository.countByCohortId(c.getId()),
                        assignmentRepository.findByCohortIdOrderByCreatedAtDesc(c.getId()).size()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StudentResponse> students(Long organizationId, Long cohortId) {
        Cohort cohort = requireCohort(organizationId, cohortId);
        return enrollmentRepository.findByCohortIdOrderByCreatedAtAsc(cohortId).stream()
                .map(e -> toStudent(e, cohort))
                .toList();
    }

    /**
     * Imports the documented CSV. Bad rows are reported, never silently dropped.
     * Existing users are enrolled without creating a duplicate account.
     */
    @Transactional
    public ImportStudentsResponse importStudents(Long organizationId, Long cohortId, MultipartFile file) {
        Cohort cohort = requireCohort(organizationId, cohortId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("CSV file is required");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("CSV must be smaller than 2 MB");
        }

        List<String> lines;
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }
            lines = Arrays.stream(content.split("\\r?\\n"))
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the CSV file");
        }
        if (lines.size() - 1 > MAX_IMPORT_ROWS) {
            throw new IllegalArgumentException("A single import is limited to " + MAX_IMPORT_ROWS + " students");
        }

        int created = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        List<StudentResponse> imported = new ArrayList<>();
        String organizationName = cohort.getOrganization().getName();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            List<String> columns = parseCsvLine(line);
            if (index == 0 && looksLikeHeader(columns)) {
                continue;
            }
            int row = index + 1;
            try {
                if (columns.size() < 2) {
                    throw new IllegalArgumentException("expected fullName,email");
                }
                String fullName = columns.get(0).trim();
                String email = columns.get(1).trim().toLowerCase(Locale.ROOT);
                String phone = columns.size() > 2 ? columns.get(2).trim() : "";
                String department = columns.size() > 3 && !columns.get(3).isBlank()
                        ? columns.get(3).trim() : cohort.getDepartment();
                Integer graduationYear = columns.size() > 4 && !columns.get(4).isBlank()
                        ? parseYear(columns.get(4)) : cohort.getGraduationYear();

                if (fullName.isBlank()) {
                    throw new IllegalArgumentException("fullName is required");
                }
                if (!EMAIL.matcher(email).matches()) {
                    throw new IllegalArgumentException("invalid email: " + email);
                }

                Optional<User> existing = userRepository.findByEmail(email);
                User user;
                if (existing.isPresent()) {
                    user = existing.get();
                    if (enrollmentRepository.existsByCohortIdAndStudentId(cohortId, user.getId())) {
                        skipped++;
                        continue;
                    }
                } else {
                    user = userRepository.save(User.builder()
                            .email(email)
                            .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                            .fullName(fullName)
                            .phone(usablePhone(phone, errors, row))
                            .role(User.Role.CANDIDATE)
                            .build());
                }

                if (!membershipRepository.existsByOrganizationIdAndUserId(organizationId, user.getId())) {
                    membershipRepository.save(Membership.builder()
                            .organization(cohort.getOrganization())
                            .user(user)
                            .role(Membership.Role.STUDENT)
                            .build());
                }

                Enrollment enrollment = enrollmentRepository.save(Enrollment.builder()
                        .cohort(cohort)
                        .student(user)
                        .build());
                imported.add(toStudent(enrollment, cohort, department, graduationYear));
                created++;

                if (existing.isEmpty()) {
                    inviteMailer.invite(email, fullName, organizationName);
                }
            } catch (IllegalArgumentException e) {
                errors.add("row " + row + ": " + e.getMessage());
            }
        }

        return ImportStudentsResponse.builder()
                .created(created)
                .skipped(skipped)
                .errors(errors)
                .students(imported)
                .build();
    }

    /** Exposed for tests: duplicate email in the same cohort is a skip, not an error. */
    public Cohort requireCohort(Long organizationId, Long cohortId) {
        return cohortRepository.findByIdAndOrganizationId(cohortId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort not found"));
    }

    private String usablePhone(String raw, List<String> errors, int row) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String phone = AuthService.normalizePhone(raw);
        if (phone != null && userRepository.existsByPhone(phone)) {
            errors.add("row " + row + ": phone already belongs to another account; imported without it");
            return null;
        }
        return phone;
    }

    private Integer parseYear(String value) {
        try {
            int year = Integer.parseInt(value.trim());
            if (year < 2000 || year > 2100) {
                throw new NumberFormatException();
            }
            return year;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("graduationYear must be 2000-2100");
        }
    }

    private boolean looksLikeHeader(List<String> columns) {
        if (columns.isEmpty()) {
            return false;
        }
        String first = columns.get(0).replace("\"", "").trim().toLowerCase(Locale.ROOT);
        return first.equals("fullname") || first.equals("full_name") || first.equals("name");
    }

    /** Minimal RFC-4180 reader: quotes, escaped quotes and commas inside quotes. */
    static List<String> parseCsvLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                columns.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        columns.add(current.toString().trim());
        return columns;
    }

    private StudentResponse toStudent(Enrollment enrollment, Cohort cohort) {
        return toStudent(enrollment, cohort, cohort.getDepartment(), cohort.getGraduationYear());
    }

    private StudentResponse toStudent(Enrollment enrollment, Cohort cohort,
                                      String department, Integer graduationYear) {
        User student = enrollment.getStudent();
        return StudentResponse.builder()
                .userId(student.getId())
                .fullName(student.getFullName())
                .email(student.getEmail())
                .phone(student.getPhone())
                .department(department)
                .graduationYear(graduationYear)
                .enrolledAt(enrollment.getCreatedAt())
                .build();
    }

    private CohortResponse toResponse(Cohort cohort, long studentCount, long assignmentCount) {
        return CohortResponse.builder()
                .id(cohort.getId())
                .name(cohort.getName())
                .department(cohort.getDepartment())
                .graduationYear(cohort.getGraduationYear())
                .studentCount(studentCount)
                .assignmentCount(assignmentCount)
                .build();
    }
}
