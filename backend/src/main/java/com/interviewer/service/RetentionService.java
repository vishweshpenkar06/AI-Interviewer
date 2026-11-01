package com.interviewer.service;

import com.interviewer.entity.Interview;
import com.interviewer.entity.User;
import com.interviewer.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-student retention. Dry-run by default: the log lists the recordings that
 * would be removed, and nothing is deleted until the operator flips
 * RETENTION_DRY_RUN=false.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionService {

    private final InterviewRepository interviewRepository;
    private final RecordingStorage recordingStorage;

    @Value("${app.retention.dry-run:true}")
    private boolean dryRun;

    @Value("${app.retention.default-days:30}")
    private int defaultRetentionDays;

    @Scheduled(cron = "${app.retention.cron:0 0 3 * * *}")
    @Transactional(readOnly = true)
    public void sweep() {
        List<Long> expired = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Interview interview : interviewRepository.findAll()) {
            User candidate = interview.getCandidate();
            int days = candidate != null && candidate.getDataRetentionDays() != null
                    ? candidate.getDataRetentionDays() : defaultRetentionDays;
            LocalDateTime reference = interview.getCompletedAt() != null
                    ? interview.getCompletedAt() : interview.getCreatedAt();
            if (reference != null && reference.isBefore(now.minusDays(days))) {
                expired.add(interview.getId());
                if (!dryRun) {
                    recordingStorage.deleteForInterview(interview.getId());
                }
            }
        }
        if (expired.isEmpty()) {
            log.info("Retention sweep ({}): nothing expired",
                    dryRun ? "dry-run" : "live");
            return;
        }
        log.info("Retention sweep ({}): {} interview recording set(s) {}: {}",
                dryRun ? "dry-run" : "live", expired.size(),
                dryRun ? "would be deleted" : "deleted", expired);
    }
}
