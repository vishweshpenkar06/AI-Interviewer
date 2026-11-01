package com.interviewer;

import com.interviewer.entity.Interview;
import com.interviewer.entity.User;
import com.interviewer.repository.InterviewRepository;
import com.interviewer.service.RecordingStorage;
import com.interviewer.service.RetentionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

/** The retention sweep must not delete anything while it is a dry run. */
@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    @Mock
    private InterviewRepository interviewRepository;

    @Mock
    private RecordingStorage recordingStorage;

    @InjectMocks
    private RetentionService retentionService;

    private Interview expiredInterview() {
        User student = User.builder()
                .id(7L)
                .email("student@example.com")
                .fullName("Student")
                .dataRetentionDays(1)
                .build();
        return Interview.builder()
                .id(99L)
                .candidate(student)
                .title("Old mock")
                .completedAt(LocalDateTime.now().minusDays(10))
                .build();
    }

    @Test
    void dryRunLogsButDoesNotDelete() {
        when(interviewRepository.findAll()).thenReturn(List.of(expiredInterview()));
        ReflectionTestUtils.setField(retentionService, "dryRun", true);
        ReflectionTestUtils.setField(retentionService, "defaultRetentionDays", 30);

        retentionService.sweep();

        verify(recordingStorage, never()).deleteForInterview(anyLong());
    }

    @Test
    void liveSweepDeletesExpiredRecordingsOnly() {
        when(interviewRepository.findAll()).thenReturn(List.of(expiredInterview()));
        ReflectionTestUtils.setField(retentionService, "dryRun", false);
        ReflectionTestUtils.setField(retentionService, "defaultRetentionDays", 30);

        retentionService.sweep();

        verify(recordingStorage).deleteForInterview(99L);
    }

    @Test
    void freshInterviewsAreLeftAlone() {
        Interview fresh = expiredInterview();
        fresh.setCompletedAt(LocalDateTime.now());
        when(interviewRepository.findAll()).thenReturn(List.of(fresh));
        ReflectionTestUtils.setField(retentionService, "dryRun", false);
        ReflectionTestUtils.setField(retentionService, "defaultRetentionDays", 30);

        retentionService.sweep();

        verify(recordingStorage, never()).deleteForInterview(anyLong());
    }
}
