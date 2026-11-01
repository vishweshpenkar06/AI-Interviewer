package com.interviewer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.entity.Resume;
import com.interviewer.entity.User;
import com.interviewer.exception.ConflictException;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.ResumeRepository;
import com.interviewer.repository.UserRepository;
import com.interviewer.security.CurrentUser;
import com.interviewer.service.AiServiceClient;
import com.interviewer.service.ResumeTextExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PASTED_TEXT = 100_000;

    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final AiServiceClient aiServiceClient;
    private final CurrentUser currentUser;
    private final com.interviewer.service.TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadResume(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "text", required = false) String directText) {
        Long userId = currentUser.id();
        User user = requireUser(userId);

        Resume resume = Resume.builder()
                .user(user)
                .organizationId(tenantContext.currentOrganizationId())
                .fileName(sanitizeFileName(file.getOriginalFilename()))
                .fileType(file.getContentType())
                .fileSize(String.valueOf(file.getSize()))
                .status(Resume.Status.UPLOADED)
                .build();

        if (directText != null && !directText.isBlank()) {
            resume.setExtractedText(limitText(directText));
            resume.setStatus(Resume.Status.PARSING);
        } else {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File is required");
            }
            if (file.getSize() > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("File is larger than 5 MB");
            }
            String extension = extensionOf(resume.getFileName());
            if (!ResumeTextExtractor.SUPPORTED_EXTENSIONS.contains(extension)) {
                throw new ConflictException(
                        "Upload a PDF, DOCX, TXT or MD file, or paste the resume text.");
            }

            try {
                byte[] bytes = file.getBytes();
                resume.setExtractedText(limitText(ResumeTextExtractor.extract(bytes, extension)));
                resume.setStatus(Resume.Status.PARSING);

                Path baseDir = Paths.get(uploadDir, "resumes").toAbsolutePath().normalize();
                Files.createDirectories(baseDir);
                Path target = baseDir.resolve(UUID.randomUUID() + "." + extension).normalize();
                if (!target.startsWith(baseDir)) {
                    throw new ConflictException("Invalid upload path");
                }
                Files.write(target, bytes);
                resume.setFilePath(target.toString());
            } catch (IOException e) {
                throw new ConflictException(e.getMessage());
            }
        }

        parseWithAi(resume);
        resume = resumeRepository.save(resume);

        Map<String, Object> response = new HashMap<>();
        response.put("id", resume.getId());
        response.put("fileName", resume.getFileName());
        response.put("status", resume.getStatus().name());
        response.put("parsedData", resume.getParsedData());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/parse-text")
    public ResponseEntity<Map<String, Object>> parseResumeText(@RequestBody Map<String, String> request) {
        Long userId = currentUser.id();
        User user = requireUser(userId);

        String text = request.get("text");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text is required");
        }
        if (text.length() > MAX_PASTED_TEXT) {
            throw new IllegalArgumentException("Resume text is too long (max " + MAX_PASTED_TEXT + " characters)");
        }
        String requestedName = request.get("fileName");

        Map<String, Object> parsed = aiServiceClient.parseResume(text);

        Resume resume = Resume.builder()
                .user(user)
                .organizationId(tenantContext.currentOrganizationId())
                .fileName(requestedName == null || requestedName.isBlank()
                        ? "pasted-resume.txt" : sanitizeFileName(requestedName))
                .filePath("pasted")
                .fileType("text/plain")
                .extractedText(text)
                .parsedData(parsed != null ? toJson(parsed) : null)
                .status(Resume.Status.PARSED)
                .build();
        resume = resumeRepository.save(resume);

        Map<String, Object> response = new HashMap<>();
        response.put("id", resume.getId());
        response.put("parsedData", parsed);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateResume(@RequestBody Map<String, Object> request) {
        if (request == null || request.get("full_name") == null || request.get("role") == null) {
            throw new IllegalArgumentException("Full name and target role are required");
        }
        Map<String, Object> generated = aiServiceClient.generateResume(request);
        if (generated == null || generated.isEmpty()) {
            throw new ConflictException("Resume generation is unavailable. Use the offline template.");
        }
        return ResponseEntity.ok(generated);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getUserResumes() {
        List<Resume> resumes = resumeRepository.findByUserIdOrderByCreatedAtDesc(currentUser.id());
        List<Map<String, Object>> response = resumes.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("fileName", r.getFileName());
            map.put("status", r.getStatus().name());
            map.put("parsedData", r.getParsedData());
            map.put("createdAt", r.getCreatedAt());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{resumeId}")
    public ResponseEntity<Map<String, Object>> getResume(@PathVariable Long resumeId) {
        Resume resume = requireOwnedResume(resumeId);

        Map<String, Object> response = new HashMap<>();
        response.put("id", resume.getId());
        response.put("fileName", resume.getFileName());
        response.put("extractedText", resume.getExtractedText());
        response.put("parsedData", resume.getParsedData());
        response.put("status", resume.getStatus().name());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{resumeId}")
    public ResponseEntity<Map<String, Object>> deleteResume(@PathVariable Long resumeId) {
        resumeRepository.delete(requireOwnedResume(resumeId));
        return ResponseEntity.ok(Map.of("message", "Resume deleted"));
    }

    private void parseWithAi(Resume resume) {
        try {
            Map<String, Object> parsed = aiServiceClient.parseResume(
                    resume.getExtractedText() != null ? resume.getExtractedText() : "");
            resume.setParsedData(parsed != null ? toJson(parsed) : null);
            resume.setStatus(Resume.Status.PARSED);
        } catch (Exception e) {
            resume.setStatus(Resume.Status.FAILED);
            resume.setErrorMessage("Resume parsing is temporarily unavailable");
        }
    }

    private Resume requireOwnedResume(Long resumeId) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));
        if (!resume.getUser().getId().equals(currentUser.id())) {
            throw new ResourceNotFoundException("Resume not found");
        }
        return resume;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private String limitText(String text) {
        return text.length() > MAX_PASTED_TEXT ? text.substring(0, MAX_PASTED_TEXT) : text;
    }

    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "resume";
        }
        String name = Paths.get(original).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
