package com.interviewer.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateConsentRequest {
    private Boolean consentRecording;
    private Boolean consentProctoring;
    private Integer dataRetentionDays;
}
