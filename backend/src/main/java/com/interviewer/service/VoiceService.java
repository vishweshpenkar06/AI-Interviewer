package com.interviewer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
public class VoiceService {

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    @Value("${deepgram.api.key:}")
    private String deepgramApiKey;

    @Value("${ai.service.token:}")
    private String aiServiceToken;

    private final RestTemplate restTemplate;

    public VoiceService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** Same optional shared secret the AI service enforces when AI_SERVICE_TOKEN is set. */
    private HttpHeaders aiHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        if (aiServiceToken != null && !aiServiceToken.isBlank()) {
            headers.set("X-AI-Service-Token", aiServiceToken);
        }
        return headers;
    }

    public String transcribeAudio(MultipartFile audioFile) {
        return (String) transcribeAudioDetailed(audioFile).getOrDefault("transcript", "");
    }

    /** Keeps the provider, language and confidence from the AI service for the UI. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> transcribeAudioDetailed(MultipartFile audioFile) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("audio", audioFile.getResource());

            HttpEntity<MultiValueMap<String, Object>> requestEntity =
                new HttpEntity<>(body, aiHeaders(MediaType.MULTIPART_FORM_DATA));

            ResponseEntity<Map> response = restTemplate.exchange(
                aiServiceUrl + "/ai/transcribe",
                HttpMethod.POST,
                requestEntity,
                Map.class
            );

            if (response.getBody() != null) {
                Map<String, Object> result = new HashMap<>(response.getBody());
                result.putIfAbsent("transcript", "");
                result.putIfAbsent("provider", "ai-service");
                result.putIfAbsent("language", "en");
                result.putIfAbsent("confidence", 0.0);
                return result;
            }
        } catch (Exception e) {
            // Fallback: return an explicit error instead of inventing a transcript.
        }
        return Map.of(
                "transcript", "",
                "provider", "unavailable",
                "language", "en",
                "confidence", 0.0,
                "error", "Transcription service is unavailable");
    }

    public byte[] synthesizeSpeech(String text, String voice, Double rate) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("text", text);
            request.put("voice", voice);
            request.put("rate", rate);

            HttpHeaders headers = aiHeaders(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                aiServiceUrl + "/ai/synthesize",
                HttpMethod.POST,
                entity,
                byte[].class
            );

            return response.getBody();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public List<Map<String, String>> getAvailableVoices() {
        return Arrays.asList(
            Map.of("id", "en-US-AndrewNeural", "name", "Andrew (Natural Male)", "language", "en-US"),
            Map.of("id", "en-US-GuyNeural", "name", "Guy (Male)", "language", "en-US"),
            Map.of("id", "en-US-JennyNeural", "name", "Jenny (Female)", "language", "en-US"),
            Map.of("id", "en-US-AriaNeural", "name", "Aria (Female)", "language", "en-US"),
            Map.of("id", "en-GB-SoniaNeural", "name", "Sonia (British Female)", "language", "en-GB"),
            Map.of("id", "en-IN-NeerjaNeural", "name", "Neerja (Indian Female)", "language", "en-IN")
        );
    }
}
