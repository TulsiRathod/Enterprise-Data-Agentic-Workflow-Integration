package com.commotion.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.commotion.onboarding",
        "com.commotion.integrations.crm"
})
public class OnboardingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnboardingApplication.class, args);
    }
}
