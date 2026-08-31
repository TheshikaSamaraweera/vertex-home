package com.democode.mlmsittu.identity.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TotpLoginRequest(
        @NotBlank(message = "REQUIRED") String challengeId,
        @NotBlank(message = "REQUIRED") @Pattern(regexp = "\\d{6}", message = "MUST_BE_SIX_DIGITS")
                String code) {}
