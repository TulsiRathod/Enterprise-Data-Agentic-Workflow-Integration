package com.commotion.onboarding.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trigger endpoint for testing or for the SQS consumer to hand off to.
 * In production the trigger comes from a Conductor task worker, not HTTP.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService service;

    public record IngestRequest(String bucket, String key) {}

    @PostMapping("/ingest")
    public ResponseEntity<OnboardingService.Result> ingest(@RequestBody IngestRequest req) {
        OnboardingService.Result result = service.handle(req.bucket(), req.key());
        return switch (result.status()) {
            case SUCCESS, SKIPPED -> ResponseEntity.ok(result);
            case FAILED           -> ResponseEntity.unprocessableEntity().body(result);
        };
    }
}
