package com.interviewer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Student invitations are optional. Without RESEND_API_KEY the import still
 * succeeds and the invite is logged, so the pilot never depends on a paid key.
 */
@Service
@Slf4j
public class InviteMailer {

    private final RestTemplate restTemplate;

    public InviteMailer() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Value("${resend.api.key:}")
    private String resendApiKey;

    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${app.mail.from:PlacementOS <onboarding@resend.dev>}")
    private String from;

    public void invite(String email, String fullName, String organizationName) {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            log.info("Invite for {} ({}) not emailed: RESEND_API_KEY is not set. Login link: {}",
                    email, organizationName, frontendUrl);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(resendApiKey);
            Map<String, Object> body = Map.of(
                    "from", from,
                    "to", List.of(email),
                    "subject", "Your " + organizationName + " placement practice account",
                    "html", "<p>Hi " + escape(fullName) + ",</p>"
                            + "<p>Your placement practice account for <strong>" + escape(organizationName)
                            + "</strong> is ready. Sign in at <a href=\"" + frontendUrl + "/login\">"
                            + frontendUrl + "/login</a> using this email address.</p>");
            restTemplate.exchange("https://api.resend.com/emails", HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);
            log.info("Invite email queued for {}", email);
        } catch (Exception e) {
            // A mail outage must never fail a 100-student import.
            log.warn("Invite email to {} failed: {}", email, e.getMessage());
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
