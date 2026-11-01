package com.interviewer.controller;

import com.interviewer.entity.User;
import com.interviewer.exception.ConflictException;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.UserRepository;
import com.interviewer.security.CurrentUser;
import com.interviewer.service.VoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceService voiceService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribeAudio(
            @RequestParam("audio") MultipartFile audioFile) {
        requireRecordingConsent();
        if (audioFile == null || audioFile.isEmpty()) {
            throw new ConflictException("No audio was uploaded");
        }
        return ResponseEntity.ok(voiceService.transcribeAudioDetailed(audioFile));
    }

    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesizeSpeech(@RequestBody Map<String, Object> request) {
        String text = (String) request.get("text");
        String voice = (String) request.getOrDefault("voice", "en-US-AriaNeural");
        Double rate = request.get("rate") instanceof Number ? ((Number) request.get("rate")).doubleValue() : 1.0;

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (text.length() > 5000) {
            throw new IllegalArgumentException("text is too long");
        }

        try {
            byte[] audioData = voiceService.synthesizeSpeech(text, voice, rate);
            return ResponseEntity.ok()
                .header("Content-Type", "audio/mpeg")
                .body(audioData);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/voices")
    public ResponseEntity<Object> getAvailableVoices() {
        return ResponseEntity.ok(voiceService.getAvailableVoices());
    }

    private void requireRecordingConsent() {
        User user = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!Boolean.TRUE.equals(user.getConsentRecording())) {
            throw new ConflictException(
                    "Recording consent is required before audio can be processed. "
                            + "Enable it in Privacy settings and try again.");
        }
    }
}
