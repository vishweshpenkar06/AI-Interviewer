package com.interviewer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recordings live on disk, never in the database. The sweep is a dry run by
 * default so a pilot can watch what would be deleted before turning it on.
 */
@Service
@Slf4j
public class RecordingStorage {

    @Value("${app.recordings.dir:./uploads/recordings}")
    private String recordingsDir;

    @Value("${app.retention.dry-run:true}")
    private boolean dryRun;

    @Value("${app.retention.default-days:30}")
    private int defaultRetentionDays;

    public Path save(Long interviewId, byte[] audio, String extension) {
        try {
            Path dir = Paths.get(recordingsDir);
            Files.createDirectories(dir);
            String ext = extension == null || extension.isBlank() ? "webm"
                    : extension.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            Path target = dir.resolve("interview-" + interviewId + "-" + System.currentTimeMillis() + "." + ext);
            Files.write(target, audio, StandardOpenOption.CREATE_NEW);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Could not store recording", e);
        }
    }

    public void deleteForInterview(Long interviewId) {
        Path dir = Paths.get(recordingsDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = "interview-" + interviewId + "-";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*")) {
            for (Path path : stream) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Could not delete recordings for interview {}: {}", interviewId, e.getMessage());
        }
    }

    public long totalBytes() {
        Path dir = Paths.get(recordingsDir);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    total += Files.size(path);
                }
            }
        } catch (IOException ignored) {
            // Storage usage is best-effort; never fail a usage call over it.
        }
        return total;
    }

    public Map<String, Object> sweep(int retentionDays, boolean overrideDryRun) {
        int days = retentionDays > 0 ? retentionDays : defaultRetentionDays;
        boolean dry = overrideDryRun;
        Path dir = Paths.get(recordingsDir);
        List<String> candidates = new ArrayList<>();
        long bytes = 0;
        if (Files.isDirectory(dir)) {
            Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path path : stream) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    try {
                        if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                            candidates.add(path.getFileName().toString());
                            bytes += Files.size(path);
                            if (!dry) {
                                Files.deleteIfExists(path);
                            }
                        }
                    } catch (IOException ignored) {
                        // A file that vanished mid-sweep is already gone.
                    }
                }
            } catch (IOException e) {
                log.warn("Retention sweep could not list {}: {}", dir, e.getMessage());
            }
        }
        log.info("Retention sweep ({}): {} file(s), {} bytes {}",
                dry ? "dry-run" : "live", candidates.size(), bytes,
                dry ? "would be deleted" : "deleted");
        return Map.of(
                "dryRun", dry,
                "retentionDays", days,
                "files", candidates,
                "fileCount", candidates.size(),
                "bytes", bytes);
    }

}
