package com.commotion.onboarding.schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record CustomerDto(
        @NotBlank String externalId,
        @NotBlank String fullName,
        @Email @NotBlank String email,
        String company,
        @NotBlank String country,
        String notes
) {}
