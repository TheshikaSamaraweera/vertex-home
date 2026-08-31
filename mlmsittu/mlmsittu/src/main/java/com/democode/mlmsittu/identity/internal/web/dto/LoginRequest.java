package com.democode.mlmsittu.identity.internal.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "REQUIRED") @Email(message = "INVALID_EMAIL") @Size(max = 320)
                String email,
        @NotBlank(message = "REQUIRED") @Size(max = 200) String password) {}
