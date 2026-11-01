package com.interviewer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.entity.CompanyTrack;
import com.interviewer.repository.CompanyTrackRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads the company track definitions from the classpath and seeds them as
 * organisation-independent templates. Assignments copy a track's config into
 * the interview so later edits to the template never rewrite history.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyTrackService {

    public static final Set<String> RUBRIC_KEYS = Set.of(
            "relevance", "technical_depth", "correctness", "clarity", "evidence");

    private static final double WEIGHT_TOLERANCE = 0.001;

    private final CompanyTrackRepository trackRepository;
    private final ObjectMapper objectMapper;

    public record TrackConfig(
            String name,
            String slug,
            Integer durationMinutes,
            Integer questionCount,
            String difficulty,
            Map<String, Double> rubricWeights,
            List<String> sections,
            String questionBankRole) {
    }

    @PostConstruct
    @Transactional
    public void seedDefaultTracks() {
        for (Resource resource : defaultResources()) {
            try (InputStream in = resource.getInputStream()) {
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                TrackConfig config = parse(raw);
                if (trackRepository.findByNameAndOrganizationIdIsNull(config.name()).isEmpty()) {
                    trackRepository.save(CompanyTrack.builder()
                            .organization(null)
                            .name(config.name())
                            .slug(config.slug())
                            .configJson(raw)
                            .active(true)
                            .build());
                    log.info("Seeded company track {}", config.name());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Invalid company track " + resource.getFilename() + ": "
                        + e.getMessage(), e);
            }
        }
    }

    public TrackConfig parse(String raw) throws Exception {
        TrackConfig config = objectMapper.readValue(raw, TrackConfig.class);
        validate(config);
        return config;
    }

    public void validate(TrackConfig config) {
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (config.slug() == null || config.slug().isBlank()) {
            throw new IllegalArgumentException("slug is required");
        }
        if (config.durationMinutes() == null || config.durationMinutes() < 5 || config.durationMinutes() > 180) {
            throw new IllegalArgumentException("durationMinutes must be 5-180");
        }
        if (config.questionCount() == null || config.questionCount() < 1 || config.questionCount() > 30) {
            throw new IllegalArgumentException("questionCount must be 1-30");
        }
        if (config.sections() == null || config.sections().isEmpty()) {
            throw new IllegalArgumentException("sections must not be empty");
        }
        Map<String, Double> weights = config.rubricWeights();
        if (weights == null || !weights.keySet().equals(RUBRIC_KEYS)) {
            throw new IllegalArgumentException("rubricWeights must contain exactly " + RUBRIC_KEYS);
        }
        double sum = 0;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("invalid weight for " + entry.getKey());
            }
            sum += value;
        }
        if (Math.abs(sum - 1.0) > WEIGHT_TOLERANCE) {
            throw new IllegalArgumentException("rubric weights must sum to 1.0 (got " + sum + ")");
        }
    }

    public List<CompanyTrack> availableTracks(Long organizationId) {
        return trackRepository.findAllAvailableToOrganization(organizationId);
    }

    public Optional<CompanyTrack> findAvailable(Long trackId, Long organizationId) {
        return trackRepository.findAvailableToOrganization(trackId, organizationId);
    }

    public TrackConfig configOf(CompanyTrack track) {
        try {
            return parse(track.getConfigJson());
        } catch (Exception e) {
            throw new IllegalStateException("Stored track " + track.getId() + " is invalid", e);
        }
    }

    public Map<String, Double> weightsOf(CompanyTrack track) {
        return configOf(track).rubricWeights();
    }

    /** Section name to a question type the evaluator understands. */
    public static String questionTypeForSection(String section) {
        return switch (section == null ? "" : section.toLowerCase(Locale.ROOT)) {
            case "aptitude", "logical", "quant" -> "SITUATIONAL";
            case "hr", "behavioral", "behavioural" -> "BEHAVIORAL";
            case "coding", "dsa", "algorithms" -> "CODING";
            case "system_design", "design" -> "SYSTEM_DESIGN";
            case "sql", "database" -> "TECHNICAL";
            default -> "TECHNICAL";
        };
    }

    private List<Resource> defaultResources() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:company-tracks/*.json");
            return Arrays.stream(resources)
                    .sorted(Comparator.comparing(Resource::getFilename,
                            Comparator.nullsLast(String::compareTo)))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read company track definitions", e);
        }
    }
}
