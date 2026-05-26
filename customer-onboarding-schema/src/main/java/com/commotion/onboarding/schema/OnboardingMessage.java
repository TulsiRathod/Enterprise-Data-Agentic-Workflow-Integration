package com.commotion.onboarding.schema;

import lombok.Builder;

import java.time.Instant;

/** Kafka payload published from the S3/SQS ingestion stage. */
@Builder
public record OnboardingMessage(
        String tenantId,
        String bucket,
        String key,
        String contentType,
        Instant receivedAt
) {}
