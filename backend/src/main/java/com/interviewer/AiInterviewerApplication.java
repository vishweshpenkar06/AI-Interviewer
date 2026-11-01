package com.interviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiInterviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiInterviewerApplication.class, args);
    }
}
