package com.interviewer;

import com.interviewer.service.RecordingStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingStorageTest {

    @TempDir
    Path tempDir;

    private RecordingStorage storage() {
        RecordingStorage storage = new RecordingStorage();
        ReflectionTestUtils.setField(storage, "recordingsDir", tempDir.toString());
        ReflectionTestUtils.setField(storage, "dryRun", true);
        ReflectionTestUtils.setField(storage, "defaultRetentionDays", 30);
        return storage;
    }

    @Test
    void dryRunReportsButKeepsOldRecordings() throws Exception {
        Path old = tempDir.resolve("interview-1-old.webm");
        Files.writeString(old, "audio");
        Files.setLastModifiedTime(old,
                java.nio.file.attribute.FileTime.from(Instant.now().minus(40, ChronoUnit.DAYS)));

        Map<String, Object> result = storage().sweep(30, true);

        assertThat((Boolean) result.get("dryRun")).isTrue();
        assertThat((Integer) result.get("fileCount")).isEqualTo(1);
        assertThat(Files.exists(old)).isTrue();
    }

    @Test
    void liveSweepDeletesOnlyExpiredRecordings() throws Exception {
        Path old = tempDir.resolve("interview-2-old.webm");
        Path fresh = tempDir.resolve("interview-3-new.webm");
        Files.writeString(old, "audio");
        Files.writeString(fresh, "audio");
        Files.setLastModifiedTime(old,
                java.nio.file.attribute.FileTime.from(Instant.now().minus(40, ChronoUnit.DAYS)));

        storage().sweep(30, false);

        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void deletingOneInterviewLeavesOthersAlone() throws Exception {
        Path first = tempDir.resolve("interview-10-a.webm");
        Path second = tempDir.resolve("interview-100-b.webm");
        Files.writeString(first, "audio");
        Files.writeString(second, "audio");

        storage().deleteForInterview(10L);

        assertThat(Files.exists(first)).isFalse();
        assertThat(Files.exists(second)).isTrue();
    }
}
